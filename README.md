# 袋鼠小院后端 · kangaroo-courtyard-backend

基于若依 RuoYi-Vue 的 Java 后端，为袋鼠小院管理端提供账号、权限和系统管理基础，并承接后续用户端业务接口建设。

## 项目简介

当前代码包含用户、角色、菜单、部门、岗位、字典、参数配置、公告、个人资料、验证码登录、文件上传下载，以及在线用户、操作日志、登录日志、缓存和服务监控等模块。采用多模块 Maven 工程，由 `ruoyi-admin` 启动。

当前仓库尚无旅居订单、微信支付或小程序报名业务控制器。`ruoyi-quartz` 和 `ruoyi-generator` 保留源码，但已在聚合 POM 和启动模块依赖中停用，默认构建不启用它们。

## 技术栈

- Java 8 编译目标、Maven 多模块项目
- Spring Boot 2.5.15、Spring Framework、Spring Security 5.7.14
- MyBatis、PageHelper、Druid 1.2.27、MySQL
- Spring Data Redis / Lettuce、JWT（JJWT 0.9.1）
- Swagger / Springfox 3、Apache POI、Fastjson2
- Quartz、Velocity 代码生成模块源码保留但未启用

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| kangaroo-courtyard-backend | 后端服务与权限基础 | [kangaroo-courtyard-backend](https://github.com/jiangyi3265/kangaroo-courtyard-backend) |
| kangaroo-courtyard-admin | Web 管理后台 | [kangaroo-courtyard-admin](https://github.com/jiangyi3265/kangaroo-courtyard-admin) |
| kangaroo-courtyard-app | 用户端与员工移动端原型 | [kangaroo-courtyard-app](https://github.com/jiangyi3265/kangaroo-courtyard-app) |

三个仓库共同承载袋鼠小院项目。当前后端和 Web 管理端提供若依基础管理能力；小程序端已实现业务页面与部分本地交互，微信登录、员工授权、旅居业务 API、支付和消息接口尚待集成，不能视为已完成生产联调。

## 快速启动

准备 JDK（兼容 Java 8 编译目标）、Maven 3、MySQL 和 Redis。

1. 新建专用开发数据库，检查并导入 `sql/bootstrap.example.sql`。该脚本包含建表和若依基础菜单/字典数据，执行前确认目标库；管理员默认停用，密码为空，不包含可用默认密码。
2. 将公开配置示例复制为本地配置（以下命令在 PowerShell 执行）：

```powershell
Copy-Item config/application.example.yml ruoyi-admin/src/main/resources/application.yml
Copy-Item config/application-druid.example.yml ruoyi-admin/src/main/resources/application-druid.yml
```

3. 在运行环境或 IDE 的环境变量配置中设置：

| 环境变量 | 用途 |
| --- | --- |
| `DB_URL` | 自己的 JDBC MySQL URL，数据库需已初始化 |
| `DB_USERNAME` / `DB_PASSWORD` | 本地数据库账号与密码 |
| `JWT_SECRET` | 自行生成的高强度随机签名密钥 |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis 地址与密码，默认地址为 localhost |
| `UPLOAD_PATH` | 上传目录，默认 `./uploadPath` |
| `DRUID_USERNAME` / `DRUID_PASSWORD` | 仅手动启用 Druid 控制台时配置；示例默认关闭控制台 |

例如 JDBC URL 的格式为 `jdbc:mysql://localhost:3306/kangaroo_courtyard?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`。不要把密码写进 URL 或提交到 Git。Spring Boot 不会自动读取根目录 `.env`，应由进程环境/IDE 注入变量。

4. 构建与启动：

```bash
mvn clean package
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认端口为 `8080`。需要登录管理后台时，在本地使用项目的 `SecurityUtils.encryptPassword`（BCrypt）生成自选密码的散列，再将管理员记录的 `password` 更新为该散列、`status` 改为 `0`；不要公开原始密码或散列。新增用户前也应在系统参数中配置自己的初始化策略。

公开仓库只包含脱敏配置与 SQL 示例；真实 `application*.yml`、原始 SQL 和上传目录被 `.gitignore` 排除，需自行准备。示例用于新环境，不要覆盖正在使用的本地配置。

## 项目结构

```text
ruoyi-admin/       启动入口、系统/监控 REST 控制器、资源配置
ruoyi-framework/   安全认证、过滤器、框架配置与基础服务
ruoyi-system/      系统实体对应的 Service、Mapper 与 SQL 映射
ruoyi-common/      公共实体、工具、注解、异常与配置
ruoyi-quartz/      定时任务源码（默认停用）
ruoyi-generator/   代码生成源码（默认停用）
config/           公开脱敏配置示例
sql/              公开初始化示例；真实本地 SQL 不提交
bin/              Maven 打包和运行辅助脚本
```

## 简历描述示例

负责袋鼠小院后端工程的初始化与配置治理，基于 Spring Boot、Spring Security、MyBatis 和 Redis 搭建可扩展的后台管理基础，整理账号权限、日志监控及多端业务接口集成边界。仅在实际完成相应功能后使用该描述，不将若依已有功能表述为个人原创开发。

## 开源来源

本工程基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)，保留上游 `LICENSE` 和源码版权声明。项目级调整与后续业务模块由本仓库独立维护。
