package datart.server.base.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GrainViewBootstrapSummary {

    private int created;

    private int updated;

    private int skipped;

    private int failed;

    private final List<GrainViewBootstrapItemResult> items = new ArrayList<>();
}
