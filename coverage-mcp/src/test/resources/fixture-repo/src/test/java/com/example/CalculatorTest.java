package com.example;

import org.testng.Assert;
import org.testng.annotations.Test;

public class CalculatorTest {

    @Test
    public void testAdd() {
        Calculator calc = new Calculator();
        Assert.assertEquals(calc.add(2, 3), 5);
        Assert.assertEquals(calc.add(-1, 1), 0);
        Assert.assertEquals(calc.add(0, 0), 0);
    }

    @Test
    public void testSubtract() {
        Calculator calc = new Calculator();
        Assert.assertEquals(calc.subtract(5, 3), 2);
        Assert.assertEquals(calc.subtract(0, 5), -5);
    }

    @Test
    public void testMultiply() {
        Calculator calc = new Calculator();
        Assert.assertEquals(calc.multiply(3, 4), 12);
        Assert.assertEquals(calc.multiply(-2, 3), -6);
        Assert.assertEquals(calc.multiply(0, 100), 0);
    }

    @Test
    public void testDivide() {
        Calculator calc = new Calculator();
        Assert.assertEquals(calc.divide(10, 2), 5);
        Assert.assertEquals(calc.divide(9, 3), 3);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testDivideByZero() {
        Calculator calc = new Calculator();
        calc.divide(10, 0);
    }
}