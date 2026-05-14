package datart.server.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrainViewBootstrapItemResult {

    private String externalOrgId;

    private String regionCode;

    private String folderId;

    private String viewId;

    /** CREATE / UPDATE / SKIP / ERROR */
    private String action;

    private String message;
}
