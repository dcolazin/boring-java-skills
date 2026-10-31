package com.example;

import org.testng.Assert;
import org.testng.annotations.Test;

public class UtilsTest {

    @Test
    public void testFormat() {
        Utils utils = new Utils();
        Assert.assertEquals(utils.format("  hello  "), "HELLO");
        Assert.assertEquals(utils.format(null), "");
        Assert.assertEquals(utils.format(""), "");
        Assert.assertEquals(utils.format("WORLD"), "WORLD");
    }

    @Test
    public void testIsEmpty() {
        Utils utils = new Utils();
        Assert.assertTrue(utils.isEmpty(null));
        Assert.assertTrue(utils.isEmpty(""));
        Assert.assertTrue(utils.isEmpty("   "));
        Assert.assertFalse(utils.isEmpty("hello"));
    }
}