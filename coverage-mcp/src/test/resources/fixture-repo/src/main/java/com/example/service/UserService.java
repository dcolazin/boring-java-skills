package com.example.service;

import com.example.User;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class UserService {
    private final Map<Integer, User> users = new HashMap<>();

    public UserService() {
        users.put(1, new User(1, "Alice", "alice@example.com"));
        users.put(2, new User(2, "Bob", "bob@example.com"));
        users.put(3, new User(3, "Charlie", "charlie@example.com"));
    }

    public Optional<User> getUser(int id) {
        return Optional.ofNullable(users.get(id));
    }

    public User createUser(String name, String email) {
        int newId = users.size() + 1;
        User user = new User(newId, name, email);
        users.put(newId, user);
        return user;
    }

    public boolean deleteUser(int id) {
        return users.remove(id) != null;
    }

    public int getUserCount() {
        return users.size();
    }
}