package com.example;

import java.util.List;
import java.util.ArrayList;

public class Statistics {
    public double mean(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return 0.0;
        }
        return numbers.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    public double median(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return 0.0;
        }
        List<Integer> sorted = new ArrayList<>(numbers);
        sorted.sort(Integer::compareTo);
        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        }
        return sorted.get(size / 2);
    }
}