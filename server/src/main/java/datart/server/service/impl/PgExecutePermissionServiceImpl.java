/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Sets;
import datart.core.base.exception.Exceptions;
import datart.core.base.exception.ParamException;
import datart.core.base.consts.ValueType;
import datart.core.base.consts.VariableTypeEnum;
import datart.core.data.provider.Column;
import datart.core.data.provider.Dataframe;
import datart.core.data.provider.ScriptType;
import datart.core.data.provider.ScriptVariable;
import datart.core.entity.User;
import datart.security.exception.AuthException;
import datart.security.exception.PermissionDeniedException;
import datart.server.base.params.TestExecuteParam;
import datart.server.service.BaseService;
import datart.server.service.DataProviderService;
import datart.server.service.PgExecutePermissionService;
import datart.server.util.SqlOrgIdExtractor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PgExecutePermissionServiceImpl extends BaseService implements PgExecutePermissionService {

    /** 仅允许 schema.table 形式，防配置被篡改注入 SQL */
    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*$");

    @Value("${datart.permission.execute-check-sql:}")
    private String executeCheckSql;

    @Value("${datart.permission.external-auth-source-id:}")
    private String externalAuthSourceId;

    /** 为 true 时，testExecute 会校验脚本中的组织 ID 是否属于当前用户在外部 PG 的授权范围 */
    @Value("${datart.permission.test-execute-org-check-enabled:false}")
    private boolean testExecuteOrgCheckEnabled;

    /**
     * 返回当前用户可访问的 org 列表：需包含列 {@code org_ids}（JSON 文本数组）或 {@code org_id}（可多行），变量 {@code $EXT_USERNAME$}。
     */
    @Value("${datart.permission.test-execute-allowed-orgs-sql:}")
    private String testExecuteAllowedOrgsSql;

    /**
     * 用户–组织映射表限定名（如 {@code public.sys_user_org}），须含 {@code user_id}、{@code org_ids}；
     * 与 {@code test-execute-allowed-orgs-sql} 二选一；自定义 SQL 优先。
     */
    @Value("${datart.permission.user-org-mapping-table:}")
    private String userOrgMappingTable;

    /** 脚本中被视为「组织 ID」的纯数字最小位数，用于减少误匹配 */
    @Value("${datart.permission.test-execute-org-id-min-digits:15}")
    private int testExecuteOrgIdMinDigits;

    private final DataProviderService dataProviderService;

    public PgExecutePermissionServiceImpl(@Lazy DataProviderService dataProviderService) {
        this.dataProviderService = dataProviderService;
    }

    @Override
    public void assertExecuteAllowed() throws Exception {
        if (StringUtils.isBlank(executeCheckSql)) {
            return;
        }
        if (StringUtils.isBlank(externalAuthSourceId)) {
            Exceptions.tr(ParamException.class, "error.param.empty", "datart.permission.external-auth-source-id");
        }
        User user = getCurrentUser();
        if (user == null || StringUtils.isBlank(user.getUsername())) {
            Exceptions.tr(AuthException.class, "login.not-login");
        }
        TestExecuteParam param = new TestExecuteParam();
        param.setSourceId(externalAuthSourceId);
        param.setScript(executeCheckSql.trim());
        param.setScriptType(ScriptType.SQL);
        param.setSize(2);
        ScriptVariable extUsername = new ScriptVariable(
                "EXT_USERNAME",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(user.getUsername()),
                false);
        param.setVariables(Collections.singletonList(extUsername));
        Dataframe df = dataProviderService.testExecuteWithoutSourcePermission(param);
        if (df == null || CollectionUtils.isEmpty(df.getRows())) {
            Exceptions.tr(PermissionDeniedException.class, "message.security.permission-denied",
                    "data-provider execute");
        }
    }

    @Override
    public String checkTestExecuteOrgAllowed(TestExecuteParam testExecuteParam) {
        if (!testExecuteOrgCheckEnabled) {
            return null;
        }
        ResolvedAllowedSql resolved = resolveAllowedOrgsSql();
        if (resolved.getConfigError() != null) {
            return resolved.getConfigError();
        }
        if (StringUtils.isBlank(resolved.getSql())) {
            return "未配置组织权限查询：请在 datart.permission 下配置 test-execute-allowed-orgs-sql 或 user-org-mapping-table";
        }
        if (StringUtils.isBlank(externalAuthSourceId)) {
            return "未配置外部数据源：datart.permission.external-auth-source-id";
        }
        if (testExecuteParam == null || testExecuteParam.getScriptType() != ScriptType.SQL) {
            return null;
        }
        User user = getCurrentUser();
        if (user == null || StringUtils.isBlank(user.getUsername())) {
            return "请先登录后再执行测试查询";
        }
        int minDigits = Math.max(8, testExecuteOrgIdMinDigits);
        Set<String> allowed;
        try {
            allowed = loadAllowedOrgIds(user.getUsername(), resolved.getSql());
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return "查询组织权限失败：" + detail;
        }
        Set<String> referenced = SqlOrgIdExtractor.extractCandidateOrgIds(testExecuteParam.getScript(), minDigits);
        if (referenced.isEmpty()) {
            return "测试 SQL 中未检测到可识别的组织 ID；请在脚本中以字面量包含当前用户有权限的 org_id（UUID、十六进制 32 位或至少 "
                    + minDigits + " 位连续数字），变量占位无法解析将一律拒绝";
        }
        if (allowed.isEmpty()) {
            return "未查询到当前用户允许的组织范围（请确认 sys_user / permissions 中存在对应 username、user_id 且 org_ids 非空）";
        }
        for (String ref : referenced) {
            if (!allowed.contains(ref)) {
                return "脚本中包含无权限的组织 ID：" + ref;
            }
        }
        return null;
    }

    /**
     * 优先使用完整 {@code test-execute-allowed-orgs-sql}；否则用 {@code user-org-mapping-table} 拼内置查询。
     */
    private ResolvedAllowedSql resolveAllowedOrgsSql() {
        if (StringUtils.isNotBlank(testExecuteAllowedOrgsSql)) {
            return new ResolvedAllowedSql(testExecuteAllowedOrgsSql.trim(), null);
        }
        if (StringUtils.isBlank(userOrgMappingTable)) {
            return new ResolvedAllowedSql("", null);
        }
        String t = userOrgMappingTable.trim();
        if (!QUALIFIED_TABLE_NAME.matcher(t).matches()) {
            return new ResolvedAllowedSql(null,
                    "datart.permission.user-org-mapping-table 格式无效，应为 schema.table（仅字母数字下划线）");
        }
        String sql = "SELECT uo.org_ids::text AS org_ids "
                + "FROM public.sys_user su "
                + "INNER JOIN " + t + " uo ON uo.user_id = su.user_id "
                + "WHERE (su.deleted IS NULL OR su.deleted = '0') "
                + "AND su.username = $EXT_USERNAME$ "
                + "LIMIT 1";
        return new ResolvedAllowedSql(sql, null);
    }

    private static final class ResolvedAllowedSql {
        private final String sql;
        /** 非 null 时表示配置错误说明，不应执行 SQL */
        private final String configError;

        private ResolvedAllowedSql(String sql, String configError) {
            this.sql = sql;
            this.configError = configError;
        }

        String getSql() {
            return sql;
        }

        String getConfigError() {
            return configError;
        }
    }

    private Set<String> loadAllowedOrgIds(String username, String sql) throws Exception {
        TestExecuteParam param = new TestExecuteParam();
        param.setSourceId(externalAuthSourceId);
        param.setScript(sql.trim());
        param.setScriptType(ScriptType.SQL);
        param.setSize(100);
        ScriptVariable extUsername = new ScriptVariable(
                "EXT_USERNAME",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(username),
                false);
        param.setVariables(Collections.singletonList(extUsername));
        Dataframe df = dataProviderService.testExecuteWithoutSourcePermission(param);
        return parseAllowedOrgIdsFromDataframe(df);
    }

    private Set<String> parseAllowedOrgIdsFromDataframe(Dataframe df) {
        Set<String> allowed = new HashSet<>();
        if (df == null || CollectionUtils.isEmpty(df.getColumns()) || CollectionUtils.isEmpty(df.getRows())) {
            return allowed;
        }
        List<Column> cols = df.getColumns();
        List<List<Object>> rows = df.getRows();
        int orgIdsIdx = indexOfColumnIgnoreCase(cols, "org_ids");
        int orgIdIdx = indexOfColumnIgnoreCase(cols, "org_id");
        if (orgIdsIdx >= 0) {
            addOrgIdsCell(allowed, rows.get(0).get(orgIdsIdx));
            return allowed;
        }
        if (orgIdIdx >= 0) {
            for (List<Object> row : rows) {
                addOneAllowed(allowed, row.get(orgIdIdx));
            }
            return allowed;
        }
        if (cols.size() == 1) {
            for (List<Object> row : rows) {
                addOneAllowed(allowed, row.get(0));
            }
        }
        return allowed;
    }

    private static int indexOfColumnIgnoreCase(List<Column> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (name.equalsIgnoreCase(cols.get(i).columnName())) {
                return i;
            }
        }
        return -1;
    }

    private void addOrgIdsCell(Set<String> allowed, Object cell) {
        if (cell == null) {
            return;
        }
        String s = cell.toString().trim();
        if (s.isEmpty()) {
            return;
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                for (int i = 0; i < arr.size(); i++) {
                    addOneAllowed(allowed, arr.get(i));
                }
            } catch (Exception ignored) {
                addOneAllowed(allowed, s);
            }
        } else {
            addOneAllowed(allowed, s);
        }
    }

    private void addOneAllowed(Set<String> allowed, Object v) {
        if (v == null) {
            return;
        }
        String c = SqlOrgIdExtractor.canonicalOrgId(v.toString(), 1);
        if (c != null) {
            allowed.add(c);
        }
    }
}
