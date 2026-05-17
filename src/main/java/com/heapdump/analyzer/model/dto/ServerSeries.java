package com.heapdump.analyzer.model.dto;

public class ServerSeries {
    private String name;
    private int[]  counts;

    public String getName()   { return name; }
    public void   setName(String v) { name = v; }
    public int[]  getCounts() { return counts; }
    public void   setCounts(int[] v) { counts = v; }
}
