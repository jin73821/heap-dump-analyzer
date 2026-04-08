package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableData {
    private List<String> headers = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
    private List<Boolean> rightAligned = new ArrayList<>();
}
