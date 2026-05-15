/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Sets;
import datart.core.base.consts.ValueType;
import datart.core.base.consts.VariableTypeEnum;
import datart.core.base.exception.Exceptions;
import datart.security.exception.AuthException;
import datart.core.data.provider.Column;
import datart.core.data.provider.Dataframe;
import datart.core.data.provider.ScriptType;
import datart.core.data.provider.ScriptVariable;
import datart.core.entity.User;
import datart.core.entity.View;
import datart.core.mappers.ext.ViewMapperExt;
import datart.server.base.dto.GrainViewBootstrapItemResult;
import datart.server.base.dto.GrainViewBootstrapSummary;
import datart.server.base.params.GrainViewBootstrapParam;
import datart.server.base.params.TestExecuteParam;
import datart.server.base.params.ViewCreateParam;
import datart.server.base.params.ViewUpdateParam;
import datart.server.service.BaseService;
import datart.server.service.DataProviderService;
import datart.server.service.PermissionGrainViewBootstrapService;
import datart.server.service.ViewService;
import datart.server.util.GrainTemperatureDataframeExpander;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 外部 PG：默认路径为两步——① 用当前登录名（与 jump-login 的 user-id 一致）匹配 {@code permissions.user_id} 取 {@code org_ids}，
 * 在 Java 中解析 JSON 数组得到 org id 列表；② 再批量查 {@code org_unit} 取 {@code region_code}。自定义 {@code grain-view-bootstrap-sql} 时仍为单次查询。
 * testExecute 与 {@link datart.server.service.impl.PgExecutePermissionServiceImpl#loadAllowedOrgIds} 相同（$EXT_USER_ID$ / $EXT_USERNAME$）。
 */
@Slf4j
@Service
public class PermissionGrainViewBootstrapServiceImpl extends BaseService implements PermissionGrainViewBootstrapService {

    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final String DEFAULT_VIEW_CONFIG =
            "{\"version\":\"1.0.0-RC.3\",\"concurrencyControl\":true,\"concurrencyControlMode\":\"DIRTY\"}";

    /**
     * 与「数据视图 → 测试执行 → 保存」写入的 model 对齐：含 {@code hierarchy}（name 为字符串 + path），
     * 否则前端 {@code transformHierarchyMeta} 仅落到 {@code columns} 时会把 name 数组原样带进 meta，
     * 与图表字段 colName（字符串）不一致，聚合/取数会拼出错误 SQL。
     */
    private static final String DEFAULT_VIEW_MODEL =
            "{\"version\":\"1.0.0-beta.4\",\"columns\":{"
                    + "\"wsdjcdh\":{\"name\":[\"wsdjcdh\"],\"type\":\"STRING\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"monitor_date\":{\"name\":[\"monitor_date\"],\"type\":\"DATE\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"monitor_time\":{\"name\":[\"monitor_time\"],\"type\":\"DATE\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"grain_avg_temper\":{\"name\":[\"grain_avg_temper\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"grain_max_temper\":{\"name\":[\"grain_max_temper\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"grain_min_temper\":{\"name\":[\"grain_min_temper\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"position_inner_temper\":{\"name\":[\"position_inner_temper\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"position_inner_humidity\":{\"name\":[\"position_inner_humidity\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"position_outer_temper\":{\"name\":[\"position_outer_temper\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"},"
                    + "\"position_outer_humidity\":{\"name\":[\"position_outer_humidity\"],\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\"}"
                    + "},\"hierarchy\":{"
                    + "\"wsdjcdh\":{\"name\":\"wsdjcdh\",\"type\":\"STRING\",\"category\":\"UNCATEGORIZED\",\"path\":[\"wsdjcdh\"]},"
                    + "\"monitor_date\":{\"name\":\"monitor_date\",\"type\":\"DATE\",\"category\":\"UNCATEGORIZED\",\"path\":[\"monitor_date\"]},"
                    + "\"monitor_time\":{\"name\":\"monitor_time\",\"type\":\"DATE\",\"category\":\"UNCATEGORIZED\",\"path\":[\"monitor_time\"]},"
                    + "\"grain_avg_temper\":{\"name\":\"grain_avg_temper\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"grain_avg_temper\"]},"
                    + "\"grain_max_temper\":{\"name\":\"grain_max_temper\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"grain_max_temper\"]},"
                    + "\"grain_min_temper\":{\"name\":\"grain_min_temper\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"grain_min_temper\"]},"
                    + "\"position_inner_temper\":{\"name\":\"position_inner_temper\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"position_inner_temper\"]},"
                    + "\"position_inner_humidity\":{\"name\":\"position_inner_humidity\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"position_inner_humidity\"]},"
                    + "\"position_outer_temper\":{\"name\":\"position_outer_temper\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"position_outer_temper\"]},"
                    + "\"position_outer_humidity\":{\"name\":\"position_outer_humidity\",\"type\":\"NUMERIC\",\"category\":\"UNCATEGORIZED\",\"path\":[\"position_outer_humidity\"]}"
                    + "}}";

    @Value("${datart.permission.external-auth-source-id:}")
    private String externalAuthSourceId;

    @Value("${datart.permission.grain-view-bootstrap-sql:}")
    private String grainViewBootstrapSql;

    @Value("${datart.permission.user-org-mapping-table:}")
    private String userOrgMappingTable;

    private final DataProviderService dataProviderService;

    private final ViewService viewService;

    private final ViewMapperExt viewMapper;

    public PermissionGrainViewBootstrapServiceImpl(@Lazy DataProviderService dataProviderService,
                                                   ViewService viewService,
                                                   ViewMapperExt viewMapper) {
        this.dataProviderService = dataProviderService;
        this.viewService = viewService;
        this.viewMapper = viewMapper;
    }

    @Override
    public List<String> fetchPermissionOrgIds(String datartUsername) throws Exception {
        if (StringUtils.isBlank(externalAuthSourceId)) {
            return Collections.emptyList();
        }
        if (StringUtils.isBlank(datartUsername)) {
            return Collections.emptyList();
        }
        String t = StringUtils.trimToEmpty(userOrgMappingTable);
        if (!QUALIFIED_TABLE_NAME.matcher(t).matches()) {
            log.warn("fetchPermissionOrgIds: datart.permission.user-org-mapping-table 无效，跳过");
            return Collections.emptyList();
        }
        return loadOrgIdsFromPermissions(datartUsername.trim(), t);
    }

    private static List<String> normalizePermissionOrgIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (StringUtils.isNotBlank(s)) {
                out.add(s.trim());
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public GrainViewBootstrapSummary syncGrainTemperatureViews(GrainViewBootstrapParam param) throws Exception {
        if (StringUtils.isBlank(externalAuthSourceId)) {
            Exceptions.base("未配置 datart.permission.external-auth-source-id，无法在外部 PG 中查询权限 org");
        }
        User user = getCurrentUser();
        if (user == null || StringUtils.isBlank(user.getUsername())) {
            Exceptions.tr(AuthException.class, "login.not-login");
        }
        List<OrgRegion> pairs;
        if (StringUtils.isNotBlank(grainViewBootstrapSql)) {
            pairs = executeOrgRegionQuery(user.getUsername(), grainViewBootstrapSql.trim());
        } else {
            List<String> preset = normalizePermissionOrgIds(param.getPermissionOrgIds());
            if (!preset.isEmpty()) {
                pairs = loadRegionsForOrgIds(preset);
            } else {
                pairs = loadOrgRegionsViaPermissionsThenOrgUnit(user.getUsername());
            }
        }
        GrainViewBootstrapSummary summary = new GrainViewBootstrapSummary();
        if (pairs.isEmpty()) {
            log.info("grain view bootstrap: no org/region rows for user={}", user.getUsername());
            return summary;
        }
        String rootParentId = StringUtils.trimToNull(param.getRootParentId());
        Map<String, String> folderByExtOrg = new HashMap<>();
        double viewIndex = 2.0;
        for (OrgRegion or : pairs) {
            if (StringUtils.isBlank(or.orgId) || StringUtils.isBlank(or.regionCode)) {
                summary.setSkipped(summary.getSkipped() + 1);
                summary.getItems().add(GrainViewBootstrapItemResult.builder()
                        .externalOrgId(or.orgId)
                        .regionCode(or.regionCode)
                        .action("SKIP")
                        .message("org_id 或 region_code 为空")
                        .build());
                continue;
            }
            try {
                String folderId = resolveFolderId(param.getOrgId(), rootParentId, or.orgId, folderByExtOrg);
                String viewName = "区域-" + or.regionCode;
                String script = buildGrainTemperatureScript(or.orgId, or.regionCode);
                List<View> existingViews = viewMapper.checkName(param.getOrgId(), folderId, viewName);
                if (CollectionUtils.isEmpty(existingViews)) {
                    ViewCreateParam create = new ViewCreateParam();
                    create.setOrgId(param.getOrgId());
                    create.setName(viewName);
                    create.setParentId(folderId);
                    create.setSourceId(param.getSourceId());
                    create.setIsFolder(false);
                    create.setScript(script);
                    create.setType(ScriptType.SQL);
                    create.setModel(DEFAULT_VIEW_MODEL);
                    create.setConfig(DEFAULT_VIEW_CONFIG);
                    create.setIndex(viewIndex);
                    create.setVariablesToCreate(new ArrayList<>());
                    View created = viewService.create(create);
                    summary.setCreated(summary.getCreated() + 1);
                    summary.getItems().add(GrainViewBootstrapItemResult.builder()
                            .externalOrgId(or.orgId)
                            .regionCode(or.regionCode)
                            .folderId(folderId)
                            .viewId(created.getId())
                            .action("CREATE")
                            .message("ok")
                            .build());
                } else {
                    View leaf = existingViews.get(0);
                    if (Boolean.TRUE.equals(leaf.getIsFolder())) {
                        summary.setFailed(summary.getFailed() + 1);
                        summary.getItems().add(GrainViewBootstrapItemResult.builder()
                                .externalOrgId(or.orgId)
                                .regionCode(or.regionCode)
                                .folderId(folderId)
                                .viewId(leaf.getId())
                                .action("ERROR")
                                .message("同名节点为目录，无法覆盖为 SQL 视图")
                                .build());
                    } else {
                        ViewUpdateParam update = new ViewUpdateParam();
                        BeanUtils.copyProperties(viewService.getViewDetail(leaf.getId()), update);
                        update.setId(leaf.getId());
                        update.setScript(script);
                        update.setType(ScriptType.SQL);
                        update.setModel(DEFAULT_VIEW_MODEL);
                        update.setConfig(DEFAULT_VIEW_CONFIG);
                        viewService.updateView(update);
                        summary.setUpdated(summary.getUpdated() + 1);
                        summary.getItems().add(GrainViewBootstrapItemResult.builder()
                                .externalOrgId(or.orgId)
                                .regionCode(or.regionCode)
                                .folderId(folderId)
                                .viewId(leaf.getId())
                                .action("UPDATE")
                                .message("ok")
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("grain view bootstrap failed org={} region={}", or.orgId, or.regionCode, e);
                summary.setFailed(summary.getFailed() + 1);
                summary.getItems().add(GrainViewBootstrapItemResult.builder()
                        .externalOrgId(or.orgId)
                        .regionCode(or.regionCode)
                        .action("ERROR")
                        .message(StringUtils.defaultString(e.getMessage()))
                        .build());
            }
            viewIndex += 0.01;
        }
        return summary;
    }

    /**
     * 默认：先按当前用户 username 查 permissions.org_ids，Java 解析出 org id，再查 org_unit 补 region。
     */
    private List<OrgRegion> loadOrgRegionsViaPermissionsThenOrgUnit(String username) throws Exception {
        String t = StringUtils.trimToEmpty(userOrgMappingTable);
        if (!QUALIFIED_TABLE_NAME.matcher(t).matches()) {
            Exceptions.base("未配置 datart.permission.grain-view-bootstrap-sql 时，须配置合法的 datart.permission.user-org-mapping-table（schema.table）");
        }
        List<String> orgIds = loadOrgIdsFromPermissions(username, t);
        if (orgIds.isEmpty()) {
            log.info("grain view bootstrap: no org_ids from permissions table {} for user={}", t, username);
            return Collections.emptyList();
        }
        log.debug("grain view bootstrap: parsed {} org id(s) from permissions for user={}", orgIds.size(), username);
        return loadRegionsForOrgIds(orgIds);
    }

    private List<String> loadOrgIdsFromPermissions(String username, String mappingTable) throws Exception {
        String sql = "SELECT p.org_ids::text AS org_ids\n"
                + "FROM " + mappingTable + " p\n"
                + "WHERE btrim(p.user_id::text) = $EXT_USER_ID$\n"
                + "   OR btrim(p.user_id::text) = $EXT_USERNAME$\n";
        Dataframe df = executeExternalSql(sql, extUserVars(username), 200);
        return parseOrgIdsFromPermissionsDataframe(df);
    }

    private List<String> parseOrgIdsFromPermissionsDataframe(Dataframe df) {
        LinkedHashSet<String> sink = new LinkedHashSet<>();
        if (df == null || CollectionUtils.isEmpty(df.getColumns()) || CollectionUtils.isEmpty(df.getRows())) {
            return new ArrayList<>();
        }
        List<Column> cols = df.getColumns();
        int idx = indexOfColumnIgnoreCase(cols, "org_ids");
        if (idx < 0 && cols.size() == 1) {
            idx = 0;
        }
        if (idx < 0) {
            Exceptions.base("permissions 查询结果须包含 org_ids 列（或仅一列时视为 org_ids）");
        }
        for (List<Object> row : df.getRows()) {
            if (row == null || idx >= row.size()) {
                continue;
            }
            addOrgIdsFromCell(sink, row.get(idx));
        }
        return new ArrayList<>(sink);
    }

    private void addOrgIdsFromCell(Set<String> sink, Object cell) {
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
                    String one = Objects.toString(arr.get(i), "").trim();
                    if (!one.isEmpty()) {
                        sink.add(one);
                    }
                }
            } catch (Exception ex) {
                log.debug("org_ids cell is not JSON array, use raw: {}", ex.toString());
                sink.add(s);
            }
        } else {
            sink.add(s);
        }
    }

    private List<OrgRegion> loadRegionsForOrgIds(List<String> orgIds) throws Exception {
        Map<String, String> orgToRegion = new LinkedHashMap<>();
        final int chunk = 200;
        for (int from = 0; from < orgIds.size(); from += chunk) {
            List<String> sub = orgIds.subList(from, Math.min(from + chunk, orgIds.size()));
            String inList = sub.stream()
                    .map(PermissionGrainViewBootstrapServiceImpl::escapeSqlLiteral)
                    .map(s -> "'" + s + "'")
                    .collect(Collectors.joining(","));
            if (inList.isEmpty()) {
                continue;
            }
            String sql = "SELECT ou.id::text AS org_id, ou.region_code::text AS region_code\n"
                    + "FROM org_unit ou\n"
                    + "WHERE ou.status = 1 AND ou.deleted = '0'\n"
                    + "  AND ou.id::text IN (" + inList + ")\n";
            Dataframe df = executeExternalSql(sql, Collections.emptyList(), 5000);
            if (df == null || CollectionUtils.isEmpty(df.getColumns()) || CollectionUtils.isEmpty(df.getRows())) {
                continue;
            }
            List<Column> cols = df.getColumns();
            int orgIdx = indexOfColumnIgnoreCase(cols, "org_id");
            int regionIdx = indexOfColumnIgnoreCase(cols, "region_code");
            if (orgIdx < 0 || regionIdx < 0) {
                Exceptions.base("org_unit 批量查询结果须包含 org_id、region_code 列（不区分大小写）");
            }
            for (List<Object> row : df.getRows()) {
                if (row == null || orgIdx >= row.size() || regionIdx >= row.size()) {
                    continue;
                }
                String org = Objects.toString(row.get(orgIdx), "").trim();
                String region = Objects.toString(row.get(regionIdx), "").trim();
                if (!org.isEmpty() && !region.isEmpty()) {
                    orgToRegion.put(org, region);
                }
            }
        }
        List<OrgRegion> ordered = new ArrayList<>();
        for (String oid : orgIds) {
            String rc = orgToRegion.get(oid);
            if (StringUtils.isNotBlank(oid) && StringUtils.isNotBlank(rc)) {
                ordered.add(new OrgRegion(oid, rc));
            }
        }
        if (ordered.size() < orgIds.size()) {
            log.info("grain view bootstrap: {} org id(s) from permissions, {} matched org_unit with region",
                    orgIds.size(), ordered.size());
        }
        return ordered;
    }

    private static List<ScriptVariable> extUserVars(String datartUsername) {
        ScriptVariable extUsername = new ScriptVariable(
                "EXT_USERNAME",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(datartUsername),
                false);
        ScriptVariable extUserId = new ScriptVariable(
                "EXT_USER_ID",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(datartUsername),
                false);
        List<ScriptVariable> vars = new ArrayList<>(2);
        vars.add(extUsername);
        vars.add(extUserId);
        return vars;
    }

    private Dataframe executeExternalSql(String sql, List<ScriptVariable> extraVars, int size) throws Exception {
        TestExecuteParam param = new TestExecuteParam();
        param.setSourceId(externalAuthSourceId);
        param.setScript(sql);
        param.setScriptType(ScriptType.SQL);
        param.setSize(size);
        param.setVariables(extraVars != null ? extraVars : Collections.emptyList());
        return dataProviderService.testExecuteWithoutSourcePermission(param);
    }

    private List<OrgRegion> executeOrgRegionQuery(String datartUsername, String sql) throws Exception {
        Dataframe df = executeExternalSql(sql, extUserVars(datartUsername), 2000);
        return parseOrgRegions(df);
    }

    private static List<OrgRegion> parseOrgRegions(Dataframe df) {
        Set<String> dedupe = new LinkedHashSet<>();
        List<OrgRegion> out = new ArrayList<>();
        if (df == null || CollectionUtils.isEmpty(df.getColumns()) || CollectionUtils.isEmpty(df.getRows())) {
            return out;
        }
        List<Column> cols = df.getColumns();
        int orgIdx = indexOfColumnIgnoreCase(cols, "org_id");
        int regionIdx = indexOfColumnIgnoreCase(cols, "region_code");
        if (orgIdx < 0 || regionIdx < 0) {
            Exceptions.base("grain-view-bootstrap-sql 查询结果须包含 org_id、region_code 两列（不区分大小写）");
        }
        for (List<Object> row : df.getRows()) {
            if (row == null || orgIdx >= row.size() || regionIdx >= row.size()) {
                continue;
            }
            String org = Objects.toString(row.get(orgIdx), "").trim();
            String region = Objects.toString(row.get(regionIdx), "").trim();
            if (org.isEmpty() || region.isEmpty()) {
                continue;
            }
            String key = org + "\u0001" + region;
            if (dedupe.add(key)) {
                out.add(new OrgRegion(org, region));
            }
        }
        out.sort(Comparator.comparing((OrgRegion x) -> x.orgId).thenComparing(x -> x.regionCode));
        return out;
    }

    private static int indexOfColumnIgnoreCase(List<Column> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (name.equalsIgnoreCase(cols.get(i).columnName())) {
                return i;
            }
        }
        return -1;
    }

    private String resolveFolderId(String datartOrgId, String rootParentId, String extOrgId, Map<String, String> cache) {
        String cached = cache.get(extOrgId);
        if (cached != null) {
            return cached;
        }
        String baseName = folderDisplayName(extOrgId);
        String folderName = baseName;
        int suffix = 0;
        while (true) {
            List<View> hit = viewMapper.checkName(datartOrgId, rootParentId, folderName);
            if (hit.isEmpty()) {
                break;
            }
            View v = hit.get(0);
            if (Boolean.TRUE.equals(v.getIsFolder())) {
                cache.put(extOrgId, v.getId());
                return v.getId();
            }
            suffix++;
            folderName = baseName + " (" + suffix + ")";
            if (folderName.length() > 255) {
                folderName = "粮温-" + suffix + "-" + Integer.toHexString(extOrgId.hashCode());
            }
            if (suffix > 50) {
                Exceptions.base("无法为外部组织解析唯一目录名: " + extOrgId);
            }
        }
        ViewCreateParam folder = new ViewCreateParam();
        folder.setOrgId(datartOrgId);
        folder.setName(folderName);
        folder.setParentId(rootParentId);
        folder.setIsFolder(true);
        folder.setScript("");
        folder.setType(ScriptType.SQL);
        folder.setIndex(1.0);
        folder.setVariablesToCreate(new ArrayList<>());
        View created = viewService.create(folder);
        cache.put(extOrgId, created.getId());
        return created.getId();
    }

    private static String folderDisplayName(String extOrgId) {
        String base = "粮温-" + extOrgId.replace('\'', ' ');
        if (base.length() <= 255) {
            return base;
        }
        return "粮温-" + Integer.toHexString(extOrgId.hashCode()) + "-" + extOrgId.substring(Math.max(0, extOrgId.length() - 120));
    }

    private static String escapeSqlLiteral(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("'", "''");
    }

    /**
     * 与业务侧约定的通用粮温 SQL（锚点 org + region，向下递归组织树）。
     */
    private static String buildGrainTemperatureScript(String externalOrgId, String regionCode) {
        String o = escapeSqlLiteral(externalOrgId);
        String r = escapeSqlLiteral(regionCode);
        return "WITH " + GrainTemperatureDataframeExpander.SCRIPT_MARKER + " RECURSIVE tree AS (\n"
                + "    SELECT ou.id\n"
                + "    FROM org_unit ou\n"
                + "    WHERE ou.id = '" + o + "'\n"
                + "      AND ou.status = 1\n"
                + "      AND ou.region_code::text = '" + r + "'\n"
                + "      AND ou.deleted = '0'\n"
                + "    UNION ALL\n"
                + "    SELECT child.id\n"
                + "    FROM org_unit child\n"
                + "    JOIN tree tr ON child.parent_id = tr.id\n"
                + "    WHERE child.status = 1\n"
                + "      AND child.deleted = '0'\n"
                + "),\n"
                + "hwdm_list AS (\n"
                + "    SELECT DISTINCT g.hwdm\n"
                + "    FROM granary_info_table g\n"
                + "    JOIN tree s ON g.org_id = s.id\n"
                + "    WHERE g.org_id IS NOT NULL\n"
                + "      AND g.deleted = '0'\n"
                + ")\n"
                + "SELECT\n"
                + "    t.wsdjcdh,\n"
                + "    t.check_date                                  AS monitor_date,\n"
                + "    t.check_time                                  AS monitor_time,\n"
                + "    t.avg_temper                                  AS grain_avg_temper,\n"
                + "    t.max_temper                                  AS grain_max_temper,\n"
                + "    t.min_temper                                  AS grain_min_temper,\n"
                + "    t.inner_temper                                AS position_inner_temper,\n"
                + "    t.inner_humidity                              AS position_inner_humidity,\n"
                + "    t.outer_temper                                AS position_outer_temper,\n"
                + "    t.outer_humidity                              AS position_outer_humidity,\n"
                + "    t.algorithm_analysis_conclusion\n"
                + "FROM temperature_data_table t\n"
                + "JOIN hwdm_list h ON t.hwdm = h.hwdm\n"
                + "WHERE t.deleted = '0'\n"
                + "ORDER BY t.wsdjcdh, t.check_date DESC, t.check_time DESC";
    }

    private static final class OrgRegion {
        final String orgId;
        final String regionCode;

        OrgRegion(String orgId, String regionCode) {
            this.orgId = orgId;
            this.regionCode = regionCode;
        }
    }
}
