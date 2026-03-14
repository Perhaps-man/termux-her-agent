package com.termux.api.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.ParcelFileDescriptor;
import android.util.JsonWriter;
import android.util.Log;

import androidx.annotation.NonNull;

import com.termux.shared.packages.PackageUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Copied from the official termux-api source and modified for in-process use:
 * - Removed TermuxPluginUtils dependency (replaced with android.util.Log).
 */
public abstract class ResultReturner {

    @SuppressLint("StaticFieldLeak")
    public static Context context;

    private static final String LOG_TAG = "ResultReturner";

    private static final String SOCKET_OUTPUT_EXTRA = "socket_output";
    private static final String SOCKET_INPUT_EXTRA  = "socket_input";

    public interface ResultWriter {
        void writeResult(PrintWriter out) throws Exception;
    }

    public static abstract class WithInput implements ResultWriter {
        protected InputStream in;
        public void setInput(InputStream inputStream) throws Exception {
            this.in = inputStream;
        }
    }

    public static abstract class BinaryOutput implements ResultWriter {
        private OutputStream out;
        public void setOutput(OutputStream outputStream) { this.out = outputStream; }
        public abstract void writeResult(OutputStream out) throws Exception;
        public final void writeResult(PrintWriter unused) throws Exception {
            writeResult(out);
            out.flush();
        }
    }

    public static abstract class WithStringInput extends WithInput {
        protected String inputString;
        protected boolean trimInput() { return true; }

        @Override
        public final void setInput(InputStream inputStream) throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int l;
            while ((l = inputStream.read(buffer)) > 0) baos.write(buffer, 0, l);
            inputString = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (trimInput()) inputString = inputString.trim();
        }
    }

    public static abstract class WithAncillaryFd implements ResultWriter {
        private LocalSocket outputSocket = null;
        private final ParcelFileDescriptor[] pfds = { null };

        public final void setOutputSocketForFds(LocalSocket outputSocket) {
            this.outputSocket = outputSocket;
        }

        public final void sendFd(PrintWriter out, int fd) {
            if (this.pfds[0] != null) {
                Logger.logStackTraceWithMessage(LOG_TAG, "File descriptor already sent", new Exception());
                return;
            }
            this.pfds[0] = ParcelFileDescriptor.adoptFd(fd);
            FileDescriptor[] fds = { pfds[0].getFileDescriptor() };
            outputSocket.setFileDescriptorsForSend(fds);
            out.print("@");
            out.flush();
            outputSocket.setFileDescriptorsForSend(null);
        }

        public final void cleanupFds() {
            if (this.pfds[0] != null) {
                try { this.pfds[0].close(); } catch (IOException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close file descriptor", e);
                }
            }
        }
    }

    public static abstract class ResultJsonWriter implements ResultWriter {
        @Override
        public final void writeResult(PrintWriter out) throws Exception {
            JsonWriter writer = new JsonWriter(out);
            writer.setIndent("  ");
            writeJson(writer);
            out.println();
        }
        public abstract void writeJson(JsonWriter out) throws Exception;
    }

    public static void noteDone(BroadcastReceiver receiver, final Intent intent) {
        returnData(receiver, intent, null);
    }

    public static void copyIntentExtras(Intent origIntent, Intent newIntent) {
        newIntent.putExtra("api_method",      origIntent.getStringExtra("api_method"));
        newIntent.putExtra(SOCKET_OUTPUT_EXTRA, origIntent.getStringExtra(SOCKET_OUTPUT_EXTRA));
        newIntent.putExtra(SOCKET_INPUT_EXTRA,  origIntent.getStringExtra(SOCKET_INPUT_EXTRA));
    }

    @SuppressLint("SdCardPath")
    public static LocalSocketAddress getApiLocalSocketAddress(@NonNull Context context,
                                                              @NonNull String socketLabel,
                                                              @NonNull String socketAddress) {
        if (socketAddress.startsWith("/")) {
            ApplicationInfo termuxApplicationInfo = PackageUtils.getApplicationInfoForPackage(
                    context, TermuxConstants.TERMUX_PACKAGE_NAME);
            if (termuxApplicationInfo == null) {
                throw new RuntimeException("Failed to get ApplicationInfo for Termux: " +
                        TermuxConstants.TERMUX_PACKAGE_NAME);
            }
            List<String> termuxAppDataDirectories = Arrays.asList(termuxApplicationInfo.dataDir,
                    "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME);
            if (!FileUtils.isPathInDirPaths(socketAddress, termuxAppDataDirectories, true)) {
                throw new RuntimeException("The " + socketLabel + " socket address \"" + socketAddress +
                        "\" is not under Termux app data directories: " + termuxAppDataDirectories);
            }
            return new LocalSocketAddress(socketAddress, Namespace.FILESYSTEM);
        } else {
            return new LocalSocketAddress(socketAddress, Namespace.ABSTRACT);
        }
    }

    public static boolean shouldRunThreadForResultRunnable(Object context) {
        return !(context instanceof IntentService);
    }

    public static void returnData(Object context, final Intent intent, final ResultWriter resultWriter) {
        final BroadcastReceiver receiver =
                (context instanceof BroadcastReceiver) ? (BroadcastReceiver) context : null;
        final Activity activity = (context instanceof Activity) ? (Activity) context : null;
        final PendingResult asyncResult = receiver != null ? receiver.goAsync() : null;

        final Throwable callerStackTrace =
                shouldRunThreadForResultRunnable(context) ? new Exception("Called by:") : null;

        final Runnable runnable = () -> {
            PrintWriter writer = null;
            LocalSocket outputSocket = null;
            try {
                outputSocket = new LocalSocket();
                String outputSocketAddress = intent.getStringExtra(SOCKET_OUTPUT_EXTRA);
                if (outputSocketAddress == null || outputSocketAddress.isEmpty())
                    throw new IOException("Missing '" + SOCKET_OUTPUT_EXTRA + "' extra");
                Logger.logDebug(LOG_TAG, "Connecting to output socket \"" + outputSocketAddress + "\"");
                outputSocket.connect(getApiLocalSocketAddress(ResultReturner.context, "output", outputSocketAddress));
                writer = new PrintWriter(outputSocket.getOutputStream());

                if (resultWriter != null) {
                    if (resultWriter instanceof WithAncillaryFd)
                        ((WithAncillaryFd) resultWriter).setOutputSocketForFds(outputSocket);
                    if (resultWriter instanceof BinaryOutput)
                        ((BinaryOutput) resultWriter).setOutput(outputSocket.getOutputStream());

                    if (resultWriter instanceof WithInput) {
                        try (LocalSocket inputSocket = new LocalSocket()) {
                            String inputSocketAddress = intent.getStringExtra(SOCKET_INPUT_EXTRA);
                            if (inputSocketAddress == null || inputSocketAddress.isEmpty())
                                throw new IOException("Missing '" + SOCKET_INPUT_EXTRA + "' extra");
                            inputSocket.connect(getApiLocalSocketAddress(
                                    ResultReturner.context, "input", inputSocketAddress));
                            ((WithInput) resultWriter).setInput(inputSocket.getInputStream());
                            resultWriter.writeResult(writer);
                        }
                    } else {
                        resultWriter.writeResult(writer);
                    }

                    if (resultWriter instanceof WithAncillaryFd)
                        ((WithAncillaryFd) resultWriter).cleanupFds();
                }

                if (asyncResult != null && receiver.isOrderedBroadcast())
                    asyncResult.setResultCode(0);
                else if (activity != null)
                    activity.setResult(0);

            } catch (Throwable t) {
                String message = "Error in " + LOG_TAG;
                if (callerStackTrace != null) t.addSuppressed(callerStackTrace);
                Logger.logStackTraceWithMessage(LOG_TAG, message, t);
                // Replaced TermuxPluginUtils.sendPluginCommandErrorNotification with plain Log
                Log.e(LOG_TAG, message, t);

                if (asyncResult != null && receiver != null && receiver.isOrderedBroadcast())
                    asyncResult.setResultCode(1);
                else if (activity != null)
                    activity.setResult(1);

            } finally {
                try {
                    if (writer != null)       writer.close();
                    if (outputSocket != null) outputSocket.close();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close", e);
                }
                try {
                    if (asyncResult != null)  asyncResult.finish();
                    else if (activity != null) activity.finish();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish", e);
                }
            }
        };

        if (shouldRunThreadForResultRunnable(context)) new Thread(runnable).start();
        else runnable.run();
    }

    public static void setContext(Context context) {
        ResultReturner.context = context.getApplicationContext();
    }
}
