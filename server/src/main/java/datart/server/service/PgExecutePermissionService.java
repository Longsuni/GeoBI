/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.service;

import datart.server.base.params.TestExecuteParam;

/**
 * data-provider execute 前：按 PostgreSQL 中配置的校验 SQL 判断是否允许执行（查到行则放行）。
 */
public interface PgExecutePermissionService {

    /**
     * 若未配置 {@code datart.permission.execute-check-sql} 则直接返回。
     * 否则用当前登录用户在 PG 中执行该校验 SQL，无任何结果行则抛出权限异常。
     */
    void assertExecuteAllowed() throws Exception;

    /**
     * 「测试执行」与「视图 /execute」共用：① 从外部 PG 加载当前用户允许的 org 集合；② 从 SQL 脚本中解析疑似组织 ID 字面量；
     * ③ 若解析不到任何 org ID 则拒绝；④ 若存在任一不在允许集合内则拒绝。非 SQL 类型不校验（返回 {@code null}）。
     * 受 {@code datart.permission.test-execute-org-check-enabled} 等配置控制。
     *
     * @return {@code null} 表示通过；非 null 为拒绝原因（可直接给前端/日志）
     */
    String checkTestExecuteOrgAllowed(TestExecuteParam testExecuteParam);
}
