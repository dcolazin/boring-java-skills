package com.example;

import org.testng.Assert;
import org.testng.annotations.Test;

public class UserTest {

    @Test
    public void testUserCreation() {
        User user = new User(1, "Alice", "alice@example.com");
        Assert.assertEquals(user.getId(), 1);
        Assert.assertEquals(user.getName(), "Alice");
        Assert.assertEquals(user.getEmail(), "alice@example.com");
    }

    @Test
    public void testToString() {
        User user = new User(1, "Alice", "alice@example.com");
        String str = user.toString();
        Assert.assertTrue(str.contains("1"));
        Assert.assertTrue(str.contains("Alice"));
        Assert.assertTrue(str.contains("alice@example.com"));
    }
}