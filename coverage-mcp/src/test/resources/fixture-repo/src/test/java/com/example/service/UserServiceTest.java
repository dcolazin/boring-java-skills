package com.example.service;

import com.example.User;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Optional;

public class UserServiceTest {

    @Test
    public void testGetUser() {
        UserService service = new UserService();
        
        Optional<User> user = service.getUser(1);
        Assert.assertTrue(user.isPresent());
        Assert.assertEquals(user.get().getName(), "Alice");
        
        Optional<User> notFound = service.getUser(999);
        Assert.assertFalse(notFound.isPresent());
    }

    @Test
    public void testCreateUser() {
        UserService service = new UserService();
        int initialCount = service.getUserCount();
        
        User newUser = service.createUser("David", "david@example.com");
        Assert.assertEquals(newUser.getName(), "David");
        Assert.assertEquals(newUser.getEmail(), "david@example.com");
        Assert.assertEquals(service.getUserCount(), initialCount + 1);
    }

    @Test
    public void testDeleteUser() {
        UserService service = new UserService();
        Assert.assertTrue(service.deleteUser(1));
        Assert.assertFalse(service.deleteUser(999));
    }

    @Test
    public void testGetUserCount() {
        UserService service = new UserService();
        Assert.assertEquals(service.getUserCount(), 3);
    }
}