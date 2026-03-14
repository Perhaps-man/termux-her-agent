package com.termux.app
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.termux.R
import androidx.fragment.app.Fragment
import com.termux.app.TermuxService
import com.termux.app.terminal.TermuxTerminalSessionClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.view.TerminalView

class TermuxEmulatorFragment : Fragment(), ServiceConnection {

    private var termuxService: TermuxService? = null

    private lateinit var terminalView: TerminalView
    private var terminalViewClient: TermuxTerminalViewClient? = null
    private var terminalSessionClient: TermuxTerminalSessionClient? = null

    private var isVisibleToUser = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_termux_emulator, container, false)
    }
    private fun host(): TermuxActivity {
        return requireActivity() as TermuxActivity
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val host = host()

        terminalSessionClient = TermuxTerminalSessionClient(host)
        terminalViewClient = TermuxTerminalViewClient(host, terminalSessionClient)

        terminalView = view.findViewById(R.id.terminal_view)
        terminalView.setTerminalViewClient(terminalViewClient)

        terminalViewClient?.onCreate()
        terminalSessionClient?.onCreate()
    }

    override fun onStart() {
        super.onStart()
        isVisibleToUser = true
        terminalSessionClient?.onStart()
        terminalViewClient?.onStart()
    }

    override fun onResume() {
        super.onResume()
        terminalSessionClient?.onResume()
        terminalViewClient?.onResume()
    }

    override fun onStop() {
        super.onStop()
        isVisibleToUser = false
        terminalSessionClient?.onStop()
        terminalViewClient?.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        try {
            requireContext().unbindService(this)
        } catch (_: Exception) {}

        // 避免 service/client 持有 Activity/Fragment 引用
        termuxService?.unsetTermuxTerminalSessionClient()
        termuxService = null

        terminalViewClient = null
        terminalSessionClient = null
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        termuxService = (service as TermuxService.LocalBinder).service

        // 3) 复刻 TermuxActivity 的“无 session 就创建”的逻辑
        //    关键点：
        //    - 如果 sessions 为空，先 bootstrap，再 addNewSession
        //    - 否则恢复当前 session
        //    - 最后把 session client 注入 service
        // 见 TermuxActivity.onServiceConnected()6

        val svc = termuxService ?: return
        val sc = terminalSessionClient ?: return

        if (svc.isTermuxSessionsEmpty()) {
            if (isVisibleToUser) {
                TermuxInstaller.setupBootstrapIfNeeded(requireActivity()) {
                    if (termuxService == null) return@setupBootstrapIfNeeded
                    sc.addNewSession(false, null)
                }
            }
        } else {
            sc.setCurrentSession(sc.getCurrentStoredSessionOrLast())
        }

        svc.setTermuxTerminalSessionClient(sc)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        termuxService = null
    }
}
