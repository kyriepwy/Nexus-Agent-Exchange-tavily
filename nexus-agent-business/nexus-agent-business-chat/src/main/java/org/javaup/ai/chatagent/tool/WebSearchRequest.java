package org.javaup.ai.chatagent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchRequest {

    private String query;
    private Integer maxResults;
}
