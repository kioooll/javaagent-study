package day9;

import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 用户画像持久化 - 跨会话记忆
 *
 * 核心思想：
 * - 用户画像（长期信息）存到文件/数据库
 * - 每次会话启动时加载画像
 * - 对话中提取新信息，更新画像
 *
 * 存储内容：
 * - 基本信息（姓名、地点、职业）
 * - 偏好（喜欢的、讨厌的）
 * - 禁忌（过敏、限制）
 * - 历史交互摘要
 */
public class UserProfileStore {

    // 存储路径
    private final Path storageDir = Paths.get("user-profiles");

    // 当前用户
    private String currentUserId;
    private UserProfile currentProfile;

    public UserProfileStore() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("创建存储目录失败", e);
        }
    }

    /**
     * 设置当前用户
     */
    public void setCurrentUser(String userId) {
        this.currentUserId = userId;
        this.currentProfile = loadProfile(userId);
    }

    /**
     * 加载用户画像
     */
    public UserProfile loadProfile(String userId) {
        Path file = storageDir.resolve(userId + ".json");
        if (!Files.exists(file)) {
            return new UserProfile(userId);
        }
        try {
            String json = Files.readString(file);
            return JSONObject.parseObject(json, UserProfile.class);
        } catch (IOException e) {
            return new UserProfile(userId);
        }
    }

    /**
     * 保存用户画像
     */
    public void saveProfile() {
        if (currentProfile == null) return;
        Path file = storageDir.resolve(currentProfile.userId + ".json");
        try {
            Files.writeString(file, JSONObject.toJSONString(currentProfile, true));
            System.out.println("已保存用户画像：" + currentProfile.userId);
        } catch (IOException e) {
            System.out.println("保存失败：" + e.getMessage());
        }
    }

    /**
     * 更新用户信息（从对话中提取）
     */
    public void updateProfile(String key, String value) {
        if (currentProfile == null) return;
        currentProfile.attributes.put(key, value);
        currentProfile.lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        saveProfile();
    }

    /**
     * 添加用户偏好
     */
    public void addPreference(String preference) {
        if (currentProfile == null) return;
        if (!currentProfile.preferences.contains(preference)) {
            currentProfile.preferences.add(preference);
            saveProfile();
        }
    }

    /**
     * 添加禁忌
     */
    public void addAllergy(String allergy) {
        if (currentProfile == null) return;
        if (!currentProfile.allergies.contains(allergy)) {
            currentProfile.allergies.add(allergy);
            saveProfile();
        }
    }

    /**
     * 获取用户画像描述（用于拼入 Prompt）
     */
    public String getProfileDescription() {
        if (currentProfile == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【用户画像】\n");

        if (!currentProfile.attributes.isEmpty()) {
            sb.append("基本信息：\n");
            for (Map.Entry<String, String> entry : currentProfile.attributes.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        if (!currentProfile.preferences.isEmpty()) {
            sb.append("偏好：").append(String.join(", ", currentProfile.preferences)).append("\n");
        }

        if (!currentProfile.allergies.isEmpty()) {
            sb.append("禁忌/过敏：").append(String.join(", ", currentProfile.allergies)).append("\n");
        }

        return sb.toString();
    }

    public UserProfile getCurrentProfile() {
        return currentProfile;
    }

    // ==================== 数据类 ====================

    public static class UserProfile {
        public String userId;
        public Map<String, String> attributes = new HashMap<>();
        public List<String> preferences = new ArrayList<>();
        public List<String> allergies = new ArrayList<>();
        public String lastUpdated;

        public UserProfile() {}  // JSON 反序列化需要

        public UserProfile(String userId) {
            this.userId = userId;
        }
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        UserProfileStore store = new UserProfileStore();

        // 模拟用户「张三」第一次会话
        System.out.println("=== 第一次会话 ===\n");
        store.setCurrentUser("zhangsan");

        // 从对话中提取信息
        store.updateProfile("name", "张三");
        store.updateProfile("location", "杭州");
        store.updateProfile("occupation", "程序员");
        store.addAllergy("花生");
        store.addPreference("篮球");
        store.addPreference("咖啡");

        System.out.println("当前画像：");
        System.out.println(store.getProfileDescription());

        // 模拟第二次会话（重启程序后）
        System.out.println("\n=== 第二次会话（重启后）===\n");
        UserProfileStore store2 = new UserProfileStore();
        store2.setCurrentUser("zhangsan");

        System.out.println("重新加载后的画像：");
        System.out.println(store2.getProfileDescription());

        // 添加新信息
        System.out.println("\n添加新信息：用户说'我最近喜欢上了游泳'");
        store2.addPreference("游泳");
        System.out.println("更新后的画像：");
        System.out.println(store2.getProfileDescription());
    }
}
