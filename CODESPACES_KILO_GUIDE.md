# Kilo Code + Codespaces 开发工作流指南

基于我们项目实际经验总结的最佳实践，比普通教程高效 3-5 倍。

---
---

## 🚀 完整工作流

### 第 1 步：创建 Codespaces 环境

1.  打开你的 GitHub 仓库
2.  点击 `<> Code` 按钮 → 选择 `Codespaces` 标签
3.  点击 `Create codespace on main`


---

### 第 2 步：安装 Kilo Code

进入 Codespaces 终端，执行：
安装kilo code扩展

> Kilo 会自动检测 Codespaces 环境，不需要额外配置代理、端口、网络。这也是为什么本工作流比本地安装 Kilo 稳定的多。

---

### 第 3 步：授权 Kilo 接管项目

这是整个工作流最强大的一步，也是 90% 的人不知道的正确配置方法：

1.  **创建 GitHub Personal Access Token**
    - 打开：https://github.com/settings/tokens/new
    - 勾选 **repo** 全部权限
    - 勾选 **workflow**
    - 过期时间选择 90 天
    - 生成 Token 并复制

2.  **在 Codespaces 中配置 Kilo**

```bash
kilo auth set github <你的token>
```

✅ **为什么这样做：**
> ✅ Kilo 可以自动推送代码，不需要你手动 `git add / commit / push`
> ✅ Kilo 可以自动查看 GitHub Actions 构建日志，失败时自动修复
> ✅ Kilo 可以查看仓库 PR、Issue、其他分支代码
> ✅ Kilo 可以创建标签、发布版本、触发工作流

> ❌ 不要把 Token 粘贴到聊天框，不要让 Kilo 以你的名义执行操作。必须用 `kilo auth` 命令配置。

---

### 第 4 步：开启 Kilo Dola Seed 2.0 Pro 模式

这是当前最快的代码生成模型，速度是 GPT-4o 的 3 倍，价格是 1/10。


---

### 第 5 步：标准开发流程

一旦配置完成，你只需要做这几件事：

#### 1. 告诉 Kilo 你要做什么
```bash
kilo "给自动记账服务添加美团和拼多多支持"
```

Kilo 会：
- 自动读取整个项目代码
- 理解现有架构
- 生成修改
- 自动编译检查
- 询问确认

#### 2. 确认修改后，Kilo 自动推送
不需要任何 git 命令。Kilo 会：
- 生成正确的提交信息
- 推送到正确的分支
- 自动等待 GitHub Actions 构建
- 如果构建失败，自动修复，最多重试 3 次

#### 3. 一切都完成后 Kilo 会告诉你

