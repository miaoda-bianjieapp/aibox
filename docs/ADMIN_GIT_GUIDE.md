# 管理员 Git 操作说明

远程仓库：`https://github.com/miaoda-bianjieapp/aibox.git`；主分支：`main`。

管理员主要负责查看 PR、确定 SQL 顺序，以及决定合并或退回修改。

## 1. 查看 PR

重点看三项：

1. 功能是否是本次要开发的内容。
2. PR 是否说明修改了核心代码。
3. PR 是否说明修改了数据库及 SQL 文件名。

发现问题时直接在 PR 中要求开发者修改。开发者修改原分支并推送后，PR 会自动更新。

## 2. 判断合并冲突

GitHub 能检查普通 Git 冲突。

PR 页面底部如果显示：

```text
This branch has conflicts that must be resolved
```

说明当前不能直接合并。让开发者先把最新 `main` 合入个人分支、解决冲突并重新推送。

需要注意：GitHub 只能发现文件内容冲突，不能发现以下问题：

- 两个 SQL 使用了相同的 Flyway 版本号。
- SQL 文件顺序不符合业务依赖。
- 两段代码虽然没有冲突，但业务逻辑互相影响。

## 3. 决定 SQL 顺序

数据库迁移文件统一使用：

```text
VyyyyMMddHHmmss__说明.sql
```

示例：

```text
V20260715143000__audio_recognition.sql
V20260715144000__video_generation.sql
```

合并多个包含 SQL 的 PR 时：

1. 先确认哪个 SQL 应该先执行。
2. 检查版本号是否重复。
3. 顺序或名称有问题时，让开发者修改文件名后再推送。
4. 已经合入 `main` 的 SQL 不允许再修改或重命名。

## 4. 合并 PR

满足以下条件即可合并：

- GitHub 没有提示冲突。
- 核心代码改动可以接受。
- SQL 名称和执行顺序正确。
- 开发者已经处理管理员提出的问题。

推荐使用 **Squash and merge**。合并后删除远程个人分支，开发者自行删除本地分支。
