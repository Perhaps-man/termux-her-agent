package com.termux.app;

import android.os.Bundle;

import com.termux.R;

/**
 * 启动页：有插件时进入插件 Activity；无插件时显示带「对话」「更新日志」按钮的启动页，
 * 用户点击「对话」进入 SimpleExecutorActivity。关联 PluginBuildPipeline（编译、重置在抽屉中）。
 */
public class LaunchActivity extends HerToolbarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (com.termux.app.DynamicPluginKt.hasPluginInstalled(this)) {
            if (com.termux.app.DynamicPluginKt.tryStartDynamicPlugin(this)) {
                finish();
                return;
            }
        }

        // 无插件时显示启动页（对话、更新日志按钮由 HerToolbarActivity 注入）
        setContentView(R.layout.activity_launch);
        // 首次进入即申请通知与存储权限，并完成 Termux 初始化，避免拖到对话页
        com.termux.app.IDEKt.requestNotificationPermissionIfNeeded(this);
        com.termux.app.IDEKt.requestStoragePermissionIfNeeded(this);
        if (com.termux.app.IDEKt.isTermuxBootstrapped()) {
            com.termux.app.IDEKt.ensureEmbeddedTerminal(this);
        } else {
            TermuxInstaller.setupBootstrapIfNeeded(this, () -> com.termux.app.IDEKt.ensureEmbeddedTerminal(this));
        }

    }
}
