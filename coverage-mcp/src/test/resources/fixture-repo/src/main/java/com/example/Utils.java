package com.example;

public class Utils {
    public String format(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toUpperCase();
    }

    public boolean isEmpty(String input) {
        return input == null || input.trim().isEmpty();
    }

    // New method added after baseline
    public String reverse(String input) {
        if (input == null) {
            return null;
        }
        return new StringBuilder(input).reverse().toString();
    }

    // New method added after baseline
    public boolean isPalindrome(String input) {
        if (input == null) {
            return false;
        }
        String clean = input.replaceAll("\\s+", "").toLowerCase();
        return clean.equals(new StringBuilder(clean).reverse().toString());
    }
}