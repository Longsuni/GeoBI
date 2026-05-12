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

package datart.server.common;

import datart.core.entity.User;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 将外部业务系统的 userId 无损编码进 Datart {@link User#getEmail()}（占位字段），避免邮箱正则与不重复校验问题。
 * 后续在 execute 等接口做权限判断时：{@code ExternalUserIdCodec.fromStoredEmail(user.getEmail())}。
 */
public final class ExternalUserIdCodec {

    private static final String DOMAIN = "external.sys";

    private ExternalUserIdCodec() {
    }

    /**
     * 写入 {@code user} 表 email 字段的占位邮箱。
     */
    public static String toStoredEmail(String externalUserId) {
        if (StringUtils.isBlank(externalUserId)) {
            return null;
        }
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(externalUserId.getBytes(StandardCharsets.UTF_8));
        return b64 + "@" + DOMAIN;
    }

    /**
     * 从占位邮箱还原外部 userId；若不是本编码格式则返回 {@code null}。
     */
    public static String fromStoredEmail(String storedEmail) {
        if (StringUtils.isBlank(storedEmail) || !storedEmail.endsWith("@" + DOMAIN)) {
            return null;
        }
        String b64 = storedEmail.substring(0, storedEmail.indexOf('@'));
        try {
            return new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
