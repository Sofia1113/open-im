---
description: 提交所有变更并直接推送到当前分支（全程无需确认）
---

将当前仓库的所有变更（含未跟踪文件）提交并直接推送到当前分支，**全程不要询问用户、不要停下来等确认**，一气呵成：

1. 运行 `git add -A` 暂存所有变更（包括新文件和删除的文件）
2. 运行 `git status --short` 查看变更概览，并查看关键变更的 diff 摘要，理解本轮变更内容
3. 若无任何变更（`git commit` 报 "nothing to commit"），直接告诉用户无需提交并结束
4. 根据变更内容生成一条符合本仓库惯例的提交信息：conventional commit 风格（type + scope + 中文描述），参考最近提交，如 `feat(server): ...`、`chore(server): ...`、`docs: ...`；信息要概括本轮变更要点，结尾追加：

   ```
   Co-Authored-By: Claude <noreply@anthropic.com>
   ```

5. 运行 `git commit` 提交（若信息较长可用 `-m` 多次传递）
6. 运行 `git push` 推送到当前分支对应的远程（本仓库 main 跟踪 origin/main）

若 push 因远程领先而失败，先 `git pull --rebase` 再重新 `git push`。完成后简要汇报：提交哈希、提交信息、推送结果。
