# Activity 编译支持情况说明

## 一、支持的情况

### 1. 代码结构
- **单文件**：仅支持一个 `MainActivity.java`
- **包名**：必须为 `com.example.plugin`
- **类名**：必须为 `MainActivity`
- **基类**：必须继承 `android.app.Activity`（不能是 `AppCompatActivity`）

### 2. 可用的 API
- **java.***：标准 Java 8 库
- **android.***：Android SDK（依赖 assets 中的 android.jar）
- **界面控件**：TextView、LinearLayout、FrameLayout、Button、ImageView、EditText、ScrollView 等

### 3. 布局方式
- **纯代码布局**：必须用 `new TextView(this)`、`setContentView(root)` 等方式，不能用 XML
- **资源引用**：只能使用 `android.R.xxx`（系统资源），不能用 `R.layout.xxx` 或 `R.drawable.xxx`（插件无自有资源）

### 4. 字符串处理
- `fixMultilineStringLiterals` 会把字符串字面量内的 `\n` 转成 `\\n`，避免 javac 报错
- 字符串内的 `\r` 会被丢弃

---

## 二、不支持 / 会失败的情况

### 1. 基类与依赖
| 情况 | 原因 |
|------|------|
| `extends AppCompatActivity` | classpath 只有 android.jar，无 androidx |
| `extends FragmentActivity` | 同上 |
| 使用 `androidx.*` | 未加入编译 classpath |
| 使用第三方库（Gradle/Maven） | 仅支持 java.* 和 android.* |

### 2. 资源与布局
| 情况 | 原因 |
|------|------|
| `setContentView(R.layout.xxx)` | 插件无 R 类，无 layout 资源 |
| `R.drawable.xxx`、`R.color.xxx` | 插件无自有资源 |
| `getResources().getIdentifier()` 引用插件资源 | 插件 dex 不含资源 |
| 使用 `@drawable/`、`@layout/` 等 | 同上 |

### 3. 多文件与语言
| 情况 | 原因 |
|------|------|
| 多个 Java 类 | 只写入 `MainActivity.java`，其他类不会被编译 |
| Kotlin 源码 | 只收集 `.java` 文件 |
| 其他包名 | 固定写入 `com/example/plugin/MainActivity.java` |

### 4. 编译与运行环境
| 情况 | 原因 |
|------|------|
| assets 缺少 `android.jar` | 编译会失败 |
| assets 缺少 `lib/`（含 D8） | d8 步骤会失败 |
| Termux 未安装 openjdk、d8 | javac/jar/d8 命令不可用 |
| 终端 session 未就绪 | 构建会直接跳过 |

### 5. 代码规范
| 情况 | 原因 |
|------|------|
| 包名不是 `com.example.plugin` | 与写入路径不一致，加载可能失败 |
| 类名不是 `MainActivity` | 与 entry_activity 配置不符 |
| 字符串中含未转义的 `\n` | 可能被 `fixMultilineStringLiterals` 误处理 |
| 使用 Java 9+ 语法 | javac 使用 `--release 8` |

---

## 三、推荐写法示例

```java
package com.example.plugin;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        TextView tv = new TextView(this);
        tv.setText("你好");
        tv.setTextSize(24);
        tv.setTextColor(Color.BLACK);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER;
        root.addView(tv, lp);
        setContentView(root);
    }
}
```

---

## 四、常见失败场景

1. **AI 生成 `extends AppCompatActivity`** → 编译报错找不到 AppCompatActivity
2. **AI 生成 `setContentView(R.layout.main)`** → 编译报错找不到 R
3. **AI 生成多行字符串未转义** → 可能被错误转义或编译失败
4. **AI 输出非 JSON 或 JSON 结构错误** → 回退到模板，不会用 AI 代码
5. **javac/d8 命令执行失败** → 需在 Termux 中安装 `pkg install openjdk-17` 等
