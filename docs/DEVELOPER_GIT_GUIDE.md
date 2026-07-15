# 开发者 Git 操作说明

远程仓库：`https://github.com/miaoda-bianjieapp/aibox.git`；主分支：`main`。

原则：**一个任务创建一个新分支，不要长期重复使用同一个个人分支。**

## 1. 开始任务

开始任务前，先拉取最新 `main`，再创建任务分支：

```powershell
git switch main
git pull --ff-only origin main
git switch -c feat/任务名称
```

示例：

```powershell
git switch -c feat/audio-recognition
```

## 2. 开发过程中

正常提交并推送自己的分支：

```powershell
git add .
git commit -m "feat: add audio recognition"
git push -u origin feat/audio-recognition
```

以下时间需要再次获取最新代码：

- 每天开始开发时。
- 准备提交 PR 前。
- 管理员通知 `main` 有重要改动时。
- GitHub 提示当前分支落后或存在冲突时。

更新方法：

```powershell
git switch main
git pull --ff-only origin main
git switch feat/audio-recognition
git merge main
```

如果出现冲突，先解决冲突、运行项目，再提交并推送。

## 3. 提交 PR

将个人分支推送到 GitHub，然后在 GitHub 创建：

```text
feat/audio-recognition -> main
```

PR 只需要说明：

```text
功能：音频识别

是否修改核心代码：否
是否修改数据库：是
SQL 文件：V20260715143000__audio_recognition.sql
```

如果修改了核心代码，简单写明修改了哪个公共模块。

管理员要求修改时，继续在原分支修改并 `push`，原 PR 会自动更新，不要重新创建 PR。

## 4. PR 合并后

确认管理员已经合并，再删除本地个人分支：

```powershell
git switch main
git pull --ff-only origin main
git branch -d feat/audio-recognition
```

如果 GitHub 没有自动删除远程分支，再执行：

```powershell
git push origin --delete feat/audio-recognition
```

不要在 PR 合并前删除本地或远程分支。
