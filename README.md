# 刷题测验APP

一款面向嵌入式（可自行选择加入题库，本项目以作者加入的嵌入式题目为主）知识学习的 Android 离线答题应用。项目使用 Java 和 Android 原生界面开发，支持按专题练习、随机刷题、限时测验和错题复习，并在本地保存学习记录。

## 功能介绍

### 练习与测验

- **随机练习**：从题库中随机抽取最多 10 道题，不限时作答。
- **专题练习**：选择知识分类，练习该分类下的题目。
- **模拟测验**：选择 10 题、20 题或全部题目，按照实际题数每题分配 1 分钟。
- **答案与解析**：普通练习确认答案后显示解析；模拟测验在交卷后显示答案和解析。
- **答题操作**：支持上一题、下一题、答题卡、收藏题目和手动交卷。未作答题目按错误计分。
- **草稿恢复**：选择答案和切换题目时保存进度，重新打开应用后可从首页继续练习。

限时测验离开答题页后，截止时间仍然有效。在答题状态下检测到超时会自动交卷；应用退出后过期的草稿，在继续答题时处理。

### 复习与统计

- **错题本**：交卷时自动收录答错及未作答的题目。
- **错题重练**：重练答对后自动标记为已掌握，也可以手动修改掌握状态；再次答错时会重新进入待巩固列表。
- **题目收藏**：收藏或取消收藏题目，方便以后查看。
- **成绩分析**：查看历史记录、累计正确率、分类正确率、最高分及最近最多 7 次成绩趋势。
- **题库扩展**：导入符合格式的 JSON 题库，并将历史成绩导出为 JSON 文件。

统计以已提交的练习和测验为准，重复练习会再次计入题次，未提交的草稿不计入统计。

## 内置题库

应用内置 **72 道中文单选题**，分为 6 个专题，每个专题 12 道：

| 专题 | 题数 |
| --- | ---: |
| C 语言 | 12 |
| MCU 与中断 | 12 |
| FreeRTOS | 12 |
| 嵌入式 Linux | 12 |
| 通信接口 | 12 |
| 调试与存储 | 12 |

题库用于学习和复习。具体芯片、接口、内核和编译器的行为，请以相应版本的官方资料为准。

## 技术栈与开发环境

| 项目 | 配置 |
| --- | --- |
| 编程语言 | Java 17 |
| 界面 | Android 原生 View，单 Activity 切换页面 |
| 本地存储 | SQLite、SharedPreferences |
| 数据格式 | JSON |
| 最低支持版本 | Android 8.0（API 26） |
| 编译与目标版本 | compileSdk 35、targetSdk 35 |
| Android Gradle Plugin | 8.9.2 |
| Gradle Wrapper | 8.11.1 |

应用的练习和数据保存不依赖网络或账号。首次构建时，Gradle 及 Android 开发工具可能需要下载依赖。

## 获取并运行

### 方式一：使用 Android Studio

1. 克隆仓库：

   ```bash
   git clone https://github.com/Luna-dusk/Android_answering_Test_APP.git
   cd Android_answering_Test_APP
   ```

2. 使用 Android Studio 打开项目根目录，等待 Gradle 同步完成。
3. 确认已安装 Android SDK Platform 35，并将 Gradle JDK 设置为 **JDK 17**。
4. 启动 Android 8.0 或更高版本的模拟器，或连接已开启 USB 调试的 Android 设备。
5. 选择 `app` 运行配置，点击 **Run** 安装并启动应用。

Android Studio 会根据本机 SDK 配置生成 `local.properties`。如果同步失败，请先检查 SDK、JDK 版本和 Gradle 依赖下载情况。

### 方式二：使用命令行构建

在项目根目录执行：

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

macOS 或 Linux：

```bash
./gradlew assembleDebug
```

成功后，调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

连接设备后，可以使用以下命令安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

覆盖安装需要新旧 APK 的签名一致。项目支持在本机存在 `.build/development.keystore` 时使用该开发签名；在其他机器上构建时通常使用该机器的调试签名。

## 使用流程

1. 打开应用，在首页选择**开始练习**，或进入题库选择专题。
2. 普通练习中选择答案并点击**确认答案**，查看正确答案和解析。
3. 如需限时作答，在首页或专题页面进入**模拟测验**并选择题量。
4. 使用**答题卡**查看作答进度并交卷；交卷后可查看成绩与逐题解析。
5. 进入**错题**页面重练，或在**分析**页面查看历史成绩和统计。
6. 暂时离开答题时选择保存并返回，下次可通过首页继续。

## 导入自定义题库

在应用中进入 **题库 → 导入 JSON 题库**，使用系统文件选择器选择 JSON 文件。项目根目录的 `题库导入示例.json` 可作为参考。

示例：

```json
{
  "version": 1,
  "questions": [
    {
      "id": "my-embedded-001",
      "category": "自定义嵌入式",
      "title": "在中断服务程序中应优先选择哪种处理方式？",
      "options": [
        "快速记录事件，将耗时工作交给任务",
        "阻塞等待用户输入",
        "无限循环",
        "无条件调用睡眠函数"
      ],
      "answer": 0,
      "explanation": "中断应尽量短小，并遵循平台对中断上下文的限制。",
      "source": ""
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `version` | 题库格式版本，目前为 `1` |
| `questions` | 题目数组 |
| `id` | 题目的唯一标识 |
| `category` | 题目分类；新分类会出现在题库中 |
| `title` | 题干 |
| `options` | 选项数组，每题 2～6 个非空选项 |
| `answer` | 正确选项的数组下标，**从 0 开始**，例如 `0` 对应 A |
| `explanation` | 答案解析 |
| `source` | 可选的参考来源 |

导入时请注意：

- 单个文件不超过 **2 MB**，每次导入 **1～1000 题**。
- 同一文件内的题目 `id` 不能重复；如果某个 `id` 已存在于本地题库，对应题目会被跳过，不会覆盖原题。
- 程序会先校验整批题目，确认无误后再写入数据库；校验失败不会只导入其中一部分。
- 当前支持 JSON 格式的单选题，不能直接导入 PDF、Word、图片或多选题。

## 导出成绩

在**分析**页面选择 **导出全部成绩记录**，通过系统文件选择器选择保存位置。导出内容为 JSON 格式，包含已提交记录中的题目、答案、成绩及时间等信息。

## 项目结构

```text
.
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/questions.json       # 内置题库
│       ├── java/cn/embedded/quiz/
│       │   ├── MainActivity.java        # 页面、交互、答题流程与草稿
│       │   ├── Question.java            # 题目模型、JSON 解析与校验
│       │   ├── QuizEngine.java          # 计分与剩余时间计算
│       │   └── Store.java               # SQLite 数据读写
│       └── res/                         # 主题、图标和插画资源
├── gradle/wrapper/                      # Gradle Wrapper 配置
├── tests/
│   ├── QuizEngineCheck.java             # 计分与计时规则检查
│   └── DeviceChecks.java                # 数据库与导入设备检查
├── 题库导入示例.json
├── build.gradle
├── settings.gradle
├── gradlew
└── gradlew.bat
```

## 检查计分与计时规则

项目提供不依赖 Android 界面的 Java 检查程序。在项目根目录使用 JDK 17 执行：

```bash
javac -encoding UTF-8 -d build/quiz-check app/src/main/java/cn/embedded/quiz/QuizEngine.java tests/QuizEngineCheck.java
java -cp build/quiz-check QuizEngineCheck
```

通过时输出：

```text
6 scoring and deadline checks passed
```

`tests/DeviceChecks.java` 用于专用模拟器上的数据库与导入检查。该检查会删除测试设备中此应用的数据库，请勿在保存了个人学习记录的设备上执行。

## 数据保存与当前限制

- 题库、错题、收藏和成绩保存在应用的 SQLite 数据库中；未完成草稿保存在 SharedPreferences 中。
- 数据保存在当前设备的应用私有空间。卸载应用或清除应用数据会删除本地学习记录。
- 当前支持单选题和离线使用，未实现账号登录、云同步、多选题或后台题库管理。
- 模拟器中已验证主要功能；不同型号的真机和更多 Android 版本仍需进一步测试。
