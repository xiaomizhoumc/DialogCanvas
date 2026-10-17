# 文档网站部署

站点地址：<https://xiaomizhoumc.github.io/DialogCanvas/>。源文件保存在 `docs/wiki/`，目录顺序与栏目名称在根目录 `mkdocs.yml` 中配置。

## 本地预览

在项目根目录执行：

```powershell
python -m venv build/docs-venv
./build/docs-venv/Scripts/python.exe -m pip install -r requirements-docs.txt
./build/docs-venv/Scripts/python.exe -m mkdocs serve
```

打开终端输出的本机地址，默认是 `http://127.0.0.1:8000/`。停止预览按 Ctrl+C。构建检查使用：

```powershell
./build/docs-venv/Scripts/python.exe -m mkdocs build --strict
./build/docs-venv/Scripts/python.exe scripts/verify-docs-site.py
```

生成的 `site/` 已加入忽略规则，不提交到源码分支。网站使用内置系统字体，不依赖 Google Fonts。

## 自动部署

GitHub 仓库 Settings → Pages → Source 选择 **GitHub Actions**。工作流为 `.github/workflows/docs.yml`。

推送文档、示例、根目录 Markdown、MkDocs 配置或依赖至 `main` 后，工作流先严格构建，再上传并部署 Pages。Pull request 只构建验证；不会发布。也可以在 Actions → Publish documentation → Run workflow 手动发布。

通过 Actions 自带的短期凭据部署，不需要在源码或仓库 Secrets 中保存个人 Token。首次使用 GitHub CLI 时，运行官方设备登录，使用你自己的 GitHub 账号授权。

## 链接处理

Wiki 内的链接仍使用相对路径。`scripts/mkdocs_hooks.py` 在构建时把跳出 `docs/wiki/` 的链接转换为 GitHub `blob/main/` 或 `tree/main/` 地址；源码文档不因此被改写，在 GitHub 中仍可直接浏览。新增外链目标须同时提交到仓库，否则对应 GitHub 页面无法访问。

## 故障排查

- 构建失败：打开 Actions 的 Build documentation，查看缺失文件或无效链接；本地使用 `mkdocs build --strict` 重现。
- 部署提示找不到 Pages：检查 Settings → Pages 是否已启用 GitHub Actions。
- 首次发布前网站返回 404：等待 Deploy to GitHub Pages 成功；成功前不代表站点已经上线。
- 更新未触发：确认修改已推送 `main`，或使用 Run workflow。
- 本地分支尚未与远程关联时，不要强制推送或覆盖远程已有文件；可在独立克隆中提交并部署文档。
