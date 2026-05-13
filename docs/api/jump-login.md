# Datart 跳转登录接口

外部系统跳转时使用：调用 `POST /api/v1/permission-auth/jump-login`，仅需传入 Datart 登录名；服务端查询外部 PostgreSQL `sys_user`，必要时自动注册（默认密码 `123456`），再登录并返回 Token 与用户信息。

## 接口一览

| 方法 | Path | 说明 |
|------|------|------|
| POST | `/api/v1/permission-auth/jump-login` | 跳转登录（无需已有 Token） |

## POST `/api/v1/permission-auth/jump-login`

**Content-Type：** `application/json`

### 请求 Body

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | string | 是 | Datart 用户名；与外部库 `sys_user` 按 username 匹配 |

### 请求示例

```json
{"username": "demo"}
```

### 响应头

| Header | 说明 |
|--------|------|
| Authorization | JWT，格式 `Bearer <token>`；后续请求业务接口时在请求头原样携带 |

### 响应 body（外部统一格式）

本接口返回 **`ExternalApiResponse`**，与其他 Datart `ResponseData` 接口不同：

| 字段 | 类型 | 说明 |
|------|------|------|
| code | number | 成功为 `200` |
| data | object | 见下表 |
| message | string | 提示文案，成功时一般为「登录成功」 |
| status | string | 成功为 `success`，失败为 `fail`（异常时仍可能走全局异常结构，见备注） |

**`data` 内字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| user | object | Datart 用户信息（`id`、`username`、`email`、`name`、`avatar`、`orgOwner` 等） |
| externalSysUser | object \| null | 外部库 `sys_user` 第一行；未配置或未查到为 `null` |
| token | string | JWT，含 `Bearer ` 前缀；与响应头 `Authorization` 一致 |

响应头 **`Authorization`** 仍会写入同一 Token，便于与 Datart 其它接口兼容。

### 成功响应示例

```json
{
  "code": 200,
  "data": {
    "user": { "id": "...", "username": "momo123", "email": "...", "name": "momo123", "orgOwner": false },
    "externalSysUser": null,
    "token": "Bearer eyJ..."
  },
  "message": "登录成功",
  "status": "success"
}
```

## 服务端配置（前置）

| 配置项 | 说明 |
|--------|------|
| `datart.permission.external-auth-source-id` | Datart 中已配置的 PostgreSQL 数据源 ID |
| `datart.jump-login.session-timeout-min` | 可选。跳转登录签发的 JWT 有效期（**分钟**）。`0` 表示与普通登录相同（`datart.security.token.timeout-min`）。设为较大值（如 `43200`≈30 天）可减少「过一段时间要重新 jump」；同时 `/sys/info` 返回的 `tokenTimeout` 会与该值取 **max**，以便前端 Cookie 与 JWT 对齐。 |

## 备注

- 用户已存在且密码已非 `123456` 时，登录会失败。
- 参数校验失败、运行时异常时，多数仍返回 Datart 全局异常包 `ResponseData`，与成功时的 `ExternalApiResponse` 不同，对接需兼容。
