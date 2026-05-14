/*
 * Datart
 * <p>
 * Copyright 2021
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package datart.server.controller;

import datart.core.base.annotations.SkipLogin;
import datart.core.base.consts.Const;
import datart.core.base.exception.ParamException;
import datart.core.entity.Source;
import datart.security.exception.AuthException;
import datart.security.exception.PermissionDeniedException;
import datart.server.base.dto.ExternalApiResponse;
import datart.server.base.dto.JumpLoginResult;
import datart.server.base.dto.OrganizationBaseInfo;
import datart.server.base.dto.ResponseData;
import datart.server.base.dto.GrainViewBootstrapSummary;
import datart.server.base.params.GrainViewBootstrapParam;
import datart.server.base.params.JumpLoginParam;
import datart.server.base.params.SourceCreateParam;
import datart.server.service.ExternalSysUserAuthService;
import datart.server.service.PermissionGrainViewBootstrapService;
import datart.server.service.OrgService;
import datart.server.service.SourceService;
import datart.server.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部权限对齐：从配置的 PostgreSQL 读取 {@code public.sys_user}；跳转登录按 {@code user_id} 建 Datart 用户，execute / 组织权限 SQL 同时注入 {@code $EXT_USERNAME$} 与 {@code $EXT_USER_ID$}（取值均为 Datart 登录名，可与外部 username 或 user_id 文本对齐）。
 */
@Slf4j
@Api
@RestController
@RequestMapping(value = "/permission-auth")
public class PermissionAuthController extends BaseController {

    @Value("${datart.jump-login.auto-create-source-enabled:false}")
    private boolean jumpLoginAutoCreateSourceEnabled;

    @Value("${datart.jump-login.auto-create-source-name:}")
    private String jumpLoginAutoCreateSourceName;

    @Value("${datart.jump-login.auto-create-source-type:JDBC}")
    private String jumpLoginAutoCreateSourceType;

    /** 与前端数据源 config 一致的 JSON 字符串；敏感信息请只写在配置/环境变量中，勿提交仓库 */
    @Value("${datart.jump-login.auto-create-source-config:}")
    private String jumpLoginAutoCreateSourceConfig;

    /** 跳转登录成功后是否按外部 PG 权限自动创建粮温目录 + SQL 视图（需能解析到 orgId 与数据源 ID） */
    @Value("${datart.jump-login.auto-sync-grain-views-enabled:true}")
    private boolean jumpLoginAutoSyncGrainViewsEnabled;

    /**
     * 未在当次跳转中自动创建/复用数据源时，用于粮温视图的 Datart 数据源 ID（须指向业务库且用户有权限）；
     * 若本跳已写入响应 {@code source}，则优先使用该源的 id。
     */
    @Value("${datart.jump-login.auto-sync-grain-views-source-id:}")
    private String jumpLoginAutoSyncGrainViewsSourceId;

    /**
     * 为 true：仅当本次为 Datart 新注册用户时才自动同步粮温视图；为 false：每次 jump-login 都尝试同步（便于联调/压测，生产慎用）
     */
    @Value("${datart.jump-login.auto-sync-grain-views-only-new-user:true}")
    private boolean jumpLoginAutoSyncGrainViewsOnlyNewUser;

    private final ExternalSysUserAuthService externalSysUserAuthService;

    private final UserService userService;

    private final SourceService sourceService;

    private final OrgService orgService;

    private final PermissionGrainViewBootstrapService permissionGrainViewBootstrapService;

    public PermissionAuthController(ExternalSysUserAuthService externalSysUserAuthService,
                                    UserService userService,
                                    SourceService sourceService,
                                    OrgService orgService,
                                    PermissionGrainViewBootstrapService permissionGrainViewBootstrapService) {
        this.externalSysUserAuthService = externalSysUserAuthService;
        this.userService = userService;
        this.sourceService = sourceService;
        this.orgService = orgService;
        this.permissionGrainViewBootstrapService = permissionGrainViewBootstrapService;
    }

    @SkipLogin
    @ApiOperation(value = "外部跳转：body 传外部 user-id（sys_user.user_id）；查 PG 后 Datart 用户名为该 id 文本并登录（默认密码 123456）；响应含 orgId。execute/测试组织权限 SQL 同时支持 $EXT_USERNAME$ 与 $EXT_USER_ID$。可选 yml 开启 datart.jump-login.auto-create-source-* 自动建源；可选 auto-sync-grain-views-* 在登录成功后自动创建粮温目录与 SQL 视图")
    @PostMapping(value = "/jump-login")
    public ExternalApiResponse<Map<String, Object>> jumpLogin(@RequestBody(required = false) JumpLoginParam param,
                                                              HttpServletResponse response) {
        if (param == null || StringUtils.isBlank(param.getUserId())) {
            return ExternalApiResponse.fail(400, "User id can not be empty");
        }
        try {
            JumpLoginResult result = userService.jumpRegisterAndLogin(param.getUserId().trim());
            response.setHeader(Const.TOKEN, result.getToken());
            Map<String, Object> data = new LinkedHashMap<>(16);
            data.put("user", result.getUser());
            data.put("externalSysUser", result.getExternalSysUser());
            data.put("token", result.getToken());
            List<OrganizationBaseInfo> orgs = orgService.listOrganizations();
            String orgId = resolveOrgIdForJumpLoginAutoSource(orgs);
            data.put("orgId", orgId);
            data.put("newlyRegistered", result.isNewlyRegistered());

            if (!jumpLoginAutoCreateSourceEnabled) {
                data.put("sourceCreateSkipped", "未开启自动创建数据源：请在 datart.jump-login 下设置 auto-create-source-enabled: true，并配置 auto-create-source-name 与 auto-create-source-config");
            } else {
                String name = StringUtils.trimToEmpty(jumpLoginAutoCreateSourceName);
                String type = StringUtils.defaultIfBlank(StringUtils.trimToNull(jumpLoginAutoCreateSourceType), "JDBC");
                String cfg = StringUtils.trimToEmpty(jumpLoginAutoCreateSourceConfig);
                if (StringUtils.isAnyBlank(name, cfg)) {
                    data.put("sourceCreateSkipped", "已开启跳转自动建源但未配置 datart.jump-login.auto-create-source-name 或 auto-create-source-config");
                } else if (orgId == null) {
                    data.put("sourceCreateSkipped", "用户未加入任何 Datart 组织，无法自动创建数据源");
                } else {
                    SourceCreateParam sourceCreateParam = new SourceCreateParam();
                    sourceCreateParam.setName(name);
                    sourceCreateParam.setType(type);
                    sourceCreateParam.setOrgId(orgId);
                    sourceCreateParam.setConfig(cfg);
                    try {
                        String parentId = StringUtils.trimToNull(sourceCreateParam.getParentId());
                        Source existing = sourceService.findExistingSourceForCreate(orgId, parentId, name);
                        if (existing != null) {
                            log.debug("jump-login: same-name source exists under org, reuse without create, orgId={}, name={}, sourceId={}",
                                    orgId, name, existing.getId());
                            data.put("source", existing);
                        } else {
                            Source source = sourceService.createSource(sourceCreateParam);
                            data.put("source", source);
                        }
                    } catch (PermissionDeniedException e) {
                        log.warn("jump-login: auto create source permission denied, orgId={}", orgId, e);
                        data.put("sourceCreateSkipped", "当前用户在该组织下无创建数据源权限（非组织 Owner 时需在角色中授予数据源创建权限）");
                    } catch (ParamException e) {
                        log.warn("jump-login: auto create source failed, orgId={}", orgId, e);
                        data.put("sourceCreateSkipped", e.getMessage());
                    }
                }
            }

            if (jumpLoginAutoSyncGrainViewsEnabled) {
                if (jumpLoginAutoSyncGrainViewsOnlyNewUser && !result.isNewlyRegistered()) {
                    data.put("grainViewBootstrapSkipped",
                            "Datart 用户已存在，跳过粮温视图自动同步（测试可设 datart.jump-login.auto-sync-grain-views-only-new-user: false）");
                } else {
                    List<String> permissionOrgIds = null;
                    try {
                        if (result.getUser() != null && StringUtils.isNotBlank(result.getUser().getUsername())) {
                            List<String> fetched = permissionGrainViewBootstrapService.fetchPermissionOrgIds(
                                    result.getUser().getUsername());
                            if (fetched != null && !fetched.isEmpty()) {
                                permissionOrgIds = fetched;
                                data.put("permissionOrgIds", permissionOrgIds);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("jump-login: fetch permission org_ids failed", e);
                        data.put("permissionOrgIdsFetchError", StringUtils.defaultString(e.getMessage()));
                    }
                    maybeSyncGrainViewsAfterJumpLogin(data, orgId, permissionOrgIds);
                }
            }

            return ExternalApiResponse.success(data, "登录成功");
        } catch (AuthException e) {
            return ExternalApiResponse.fail(401, e.getMessage());
        } catch (PermissionDeniedException e) {
            return ExternalApiResponse.fail(403, e.getMessage());
        } catch (ParamException e) {
            return ExternalApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.warn("jump-login failed", e);
            String msg = StringUtils.defaultIfBlank(e.getMessage(), "jump-login error");
            return ExternalApiResponse.fail(500, msg);
        }
    }

    /**
     * 与前端在「当前组织」下新建数据源一致：优先使用当前用户为 {@link datart.security.manager.DatartSecurityManager#isOrgOwner(String)} 的组织；
     * 否则使用用户所属第一个组织（TEAM 普通成员等场景需管理员预先配置数据源创建类权限）。
     */
    private String resolveOrgIdForJumpLoginAutoSource(List<OrganizationBaseInfo> orgs) {
        if (orgs == null || orgs.isEmpty()) {
            return null;
        }
        for (OrganizationBaseInfo o : orgs) {
            if (securityManager.isOrgOwner(o.getId())) {
                return o.getId();
            }
        }
        return orgs.get(0).getId();
    }

    /**
     * 登录态已建立后执行：按外部 PG 权限批量创建/更新粮温目录与 SQL 视图；失败只写入响应字段，不阻断登录。
     *
     * @param datartOrgId   Datart 侧组织 ID（视图挂在哪个组织下）
     * @param permissionOrgIds 外部 permissions 解析出的组织 id；非空则传入同步服务，避免重复查 permissions；可为 null
     */
    private void maybeSyncGrainViewsAfterJumpLogin(Map<String, Object> data, String datartOrgId, List<String> permissionOrgIds) {
        if (!jumpLoginAutoSyncGrainViewsEnabled) {
            return;
        }
        if (StringUtils.isBlank(datartOrgId)) {
            data.put("grainViewBootstrapSkipped", "无可用 Datart 组织 orgId，跳过粮温视图同步");
            return;
        }
        String sourceId = null;
        Object sourceObj = data.get("source");
        if (sourceObj instanceof Source) {
            sourceId = ((Source) sourceObj).getId();
        }
        if (StringUtils.isBlank(sourceId)) {
            sourceId = StringUtils.trimToNull(jumpLoginAutoSyncGrainViewsSourceId);
        }
        if (StringUtils.isBlank(sourceId)) {
            data.put("grainViewBootstrapSkipped", "未解析到数据源 ID：请先成功自动建源/复用同名源，或配置 datart.jump-login.auto-sync-grain-views-source-id");
            return;
        }
        try {
            GrainViewBootstrapParam bootstrapParam = new GrainViewBootstrapParam();
            bootstrapParam.setOrgId(datartOrgId);
            bootstrapParam.setSourceId(sourceId);
            if (permissionOrgIds != null && !permissionOrgIds.isEmpty()) {
                bootstrapParam.setPermissionOrgIds(permissionOrgIds);
            }
            GrainViewBootstrapSummary summary = permissionGrainViewBootstrapService.syncGrainTemperatureViews(bootstrapParam);
            data.put("grainViewBootstrap", summary);
        } catch (Exception e) {
            log.warn("jump-login: grain view bootstrap failed, orgId={}, sourceId={}", datartOrgId, sourceId, e);
            data.put("grainViewBootstrapSkipped", StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @ApiOperation(value = "当前登录用户在外部 sys_user 表中的基础信息（用于权限校验）")
    @GetMapping(value = "/external-sys-user")
    public ResponseData<Map<String, Object>> getExternalSysUser() throws Exception {
        return ResponseData.success(externalSysUserAuthService.loadExternalSysUserForCurrentLogin());
    }

    @ApiOperation(value = "按外部 PG 权限为当前用户批量同步粮温 SQL 视图（一级目录=外部组织，二级视图=区域）；查询走 external-auth-source-id，与 execute 权限链一致")
    @PostMapping(value = "/sync-grain-temperature-views")
    public ResponseData<GrainViewBootstrapSummary> syncGrainTemperatureViews(@Valid @RequestBody GrainViewBootstrapParam param) throws Exception {
        return ResponseData.success(permissionGrainViewBootstrapService.syncGrainTemperatureViews(param));
    }
}
