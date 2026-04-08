package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentDetailParsed {
    private String className;
    private ComponentMetadata metadata;
    private List<ComponentSection> sections = new ArrayList<>();
    private boolean parsedSuccessfully;
}
