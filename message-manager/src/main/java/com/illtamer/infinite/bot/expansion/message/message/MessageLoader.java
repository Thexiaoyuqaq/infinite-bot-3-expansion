package com.illtamer.infinite.bot.expansion.message.message;

import com.illtamer.infinite.bot.api.Pair;
import com.illtamer.infinite.bot.api.util.Assert;
import com.illtamer.infinite.bot.expansion.message.InputStreamSupplier;
import com.illtamer.infinite.bot.expansion.message.MessageManager;
import com.illtamer.infinite.bot.expansion.message.pojo.Image;
import com.illtamer.infinite.bot.expansion.message.pojo.*;
import com.illtamer.infinite.bot.minecraft.api.IExpansion;
import com.illtamer.infinite.bot.minecraft.util.ExpansionUtil;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.awt.*;
import java.io.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class MessageLoader {

    // 自定义静态变量Map
    public static final Map<String, String> CUSTOM_PLACEHOLDERS = new HashMap<>();
    public static final List<Pair<MessageNode, Object>> MESSAGE_NODES = new ArrayList<>();
    private static Logger logger;

    private static int getImageTimeout;
    private static Pair<String, Integer> proxy;
    private static RequestConfig requestConfig;

    public static void init(FileConfiguration configuration, File dataFolder, IExpansion expansion) {
        logger = MessageManager.getInstance().getLogger();
        if (CUSTOM_PLACEHOLDERS.size() != 0) CUSTOM_PLACEHOLDERS.clear();
        if (MESSAGE_NODES.size() != 0) MESSAGE_NODES.clear();
        getImageTimeout = configuration.getInt("get-image-timeout", 10) * 1000;
        final ConfigurationSection proxySection = configuration.getConfigurationSection("proxy");
        if (proxySection != null && proxySection.getBoolean("enable")) {
            proxy = new Pair<>(proxySection.getString("host"), proxySection.getInt("port"));
            logger.info("图片资源代理已配置：" + proxy);
        }
        final RequestConfig.Builder builder = RequestConfig.custom()
                .setConnectTimeout(getImageTimeout)
                .setSocketTimeout(getImageTimeout);
        if (proxy != null) {
            builder.setProxy(new HttpHost(proxy.getKey(), proxy.getValue())).setAuthenticationEnabled(true);
        }
        requestConfig = builder.build();
        Optional.ofNullable(configuration.getConfigurationSection("custom-placeholder"))
                .orElseThrow(() -> new IllegalArgumentException("custom-placeholder节点不存在！"))
                .getValues(false).forEach((k, v) -> CUSTOM_PLACEHOLDERS.put(k, String.valueOf(v)));
        final String folderName = configuration.getString("folder", "/message");
        File folder = new File(dataFolder, folderName);
        logger.info("加载了 " + CUSTOM_PLACEHOLDERS.size() + " 个自定义静态变量");
        if (!folder.exists()) {
            logger.warn("消息节点资源文件夹不存在，默认目录与实例配置创建在: " + folder.getAbsolutePath());
            folder.mkdirs();
            InputStream input = expansion.getResource("message/examples.yml");
            ExpansionUtil.savePluginResource(folderName + "/examples.yml", false, dataFolder, (path) -> input);
        }
        final File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            logger.info("未找到可用的消息节点");
            return;
        }
        for (File file : files) {
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            MESSAGE_NODES.addAll(yaml.getKeys(false).stream()
                    .map(key -> deserialize(Optional.ofNullable(yaml.getConfigurationSection(key))
                            .orElseThrow(() -> new IllegalArgumentException("文件 " + file.getName() + " 中节点 " + key + " 解析失败"))))
                    .collect(Collectors.toList()));
        }
        logger.info("加载了 " + MESSAGE_NODES.size() + " 个消息节点");
    }

    private static Pair<MessageNode, Object> deserialize(ConfigurationSection section) {
        MessageNode node = new MessageNode();
        node.setLimit(deserializeLimit(section.getConfigurationSection("limit")));
        node.setTrigger(deserializeTrigger(section.getConfigurationSection("trigger")));
        node.setPapi(section.getBoolean("papi"));
        node.setShowType(MessageNode.ShowType.parse(section.getString("show-type")));
        node.setContent(section.getStringList("content"));
        Object content = null;
        if (node.getShowType() == MessageNode.ShowType.IMAGE)
            content = doDeserializeImage(section.getConfigurationSection(MessageNode.ShowType.IMAGE.getValue()));
        else if (node.getShowType() == MessageNode.ShowType.API) {
            content = doDeserializeApi(section.getConfigurationSection(MessageNode.ShowType.API.getValue()));
        } else if (node.getShowType() == MessageNode.ShowType.COMMAND) {
            content = doDeserializeCommand(section.getConfigurationSection(MessageNode.ShowType.COMMAND.getValue()));
        }
        return new Pair<>(node, content);
    }

    private static Limit deserializeLimit(ConfigurationSection section) {
        Limit limit = new Limit();
        limit.setMaxPerMinute(section.getInt("max-per-minute"));
        limit.setUserTriggerInterval(section.getInt("user-trigger-interval"));
        return limit;
    }

    private static Trigger deserializeTrigger(ConfigurationSection section) {
        Assert.notNull(section, "Can't find .trigger");
        assert section != null;
        Trigger trigger = new Trigger();
        trigger.setSource(Trigger.Source.parse(section.getString("source")));
        trigger.setAdmin(section.getBoolean("admin"));
        trigger.setBind(section.getBoolean("bind"));
        trigger.setType(Trigger.Type.parse(section.getString("type")));
        trigger.setFilter(section.getBoolean("filter"));
        trigger.setKeys(section.getStringList("keys"));
        return trigger;
    }

    private static Image doDeserializeImage(ConfigurationSection section) {
        Assert.notNull(section, "Can't find .image");
        final ConfigurationSection font = section.getConfigurationSection("font");
        Assert.notNull(font, "Can't find .font");
        final ConfigurationSection setting = section.getConfigurationSection("setting");
        Assert.notNull(setting, "Can't find .setting");
        final ConfigurationSection insertImage = section.getConfigurationSection("insert-image");

        Image image = new Image();
        {
            Font _font = new Font(font.getString("name"), font.getInt("style"), font.getInt("size"));
            image.setFont(_font);
        }
        {
            Image.Setting _setting = new Image.Setting();
            _setting.setSource(parseSource(setting.getString("source")));
            _setting.setWidth(setting.getInt("width"));
            _setting.setHeight(setting.getInt("height"));
            _setting.setStartX(setting.getInt("start-x"));
            _setting.setStartY(setting.getInt("start-y"));
            _setting.setSpace(setting.getInt("space"));
            final String color = Objects.requireNonNull(setting.getString("color"));
            final String[] splits = color.split("\\|");
            _setting.setColor(new Color(Integer.parseInt(splits[0]), Integer.parseInt(splits[1]), Integer.parseInt(splits[2])));
            image.setSetting(_setting);
        }
        {
            List<Image.InsertImage> insertImages = new ArrayList<>();
            if (insertImage != null) {
                for (String key : insertImage.getKeys(false)) {
                    Image.InsertImage _insertImage = new Image.InsertImage();
                    final ConfigurationSection imageSection = insertImage.getConfigurationSection(key);
                    _insertImage.setSource(parseSource(imageSection.getString("source")));
                    _insertImage.setWidth(imageSection.getInt("width"));
                    _insertImage.setHeight(imageSection.getInt("height"));
                    _insertImage.setStartX(imageSection.getInt("start-x"));
                    _insertImage.setStartY(imageSection.getInt("start-y"));
                    insertImages.add(_insertImage);
                }
            }
            image.setInsertImages(insertImages);
        }
        return image;
    }

    private static Api doDeserializeApi(ConfigurationSection section) {
        Assert.notNull(section, "Can't find .api");
        Api api = new Api();
        api.setUrl(Objects.requireNonNull(section.getString("url")));
        return api;
    }

    private static Command doDeserializeCommand(ConfigurationSection section) {
        Assert.notNull(section, "Can't find .command");
        Command command = new Command();
        command.setType(Command.Type.parse(section.getString("type")));
        command.setOp(section.getBoolean("op"));
        command.setRegx(section.getString("regx"));
        return command;
    }

    private static InputStreamSupplier parseSource(String source) {
        Assert.notEmpty(source, "source can not be empty");
        if (source.startsWith("url:")) {
            String url = source.substring("url:".length());
            return () -> {
                final Pair<Integer, InputStream> pair = getInputStream(url);
                if (pair.getKey() != 200) {
                    throw new IllegalArgumentException("资源请求失败 " + pair.getKey() + " url:" + url);
                }
                return pair.getValue();
            };
        } else if (source.startsWith("file:")) {
            String path = source.substring("file:".length());
            return () -> new FileInputStream(path);
        }
        throw new IllegalArgumentException(source);
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; ++ i) {
            FileOutputStream out = new FileOutputStream("C:\\Users\\Bacon\\Desktop\\" + System.currentTimeMillis() + ".png");
            String url = "http://api.illtamer.com/random?url=https://api.ixiaowai.cn/api/api.php&url=https://api.ixiaowai.cn/mcapi/mcapi.php&url=http://api.btstu.cn/sjbz/?lx=dongman&url=http://www.dmoe.cc/random.php&url=http://api.btstu.cn/sjbz/?lx=suiji&url=http://img.xjh.me/random_img.php&url=https://img.xjh.me/random_img.php";
            final InputStream pair = getInputStream(url).getValue();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = pair.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
        }
    }

    private static Pair<Integer, InputStream> getInputStream(String url) throws IOException {
        int status;
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpGet httpGet = new HttpGet(url);
            httpGet.setHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36");
            httpGet.setConfig(requestConfig);
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                status = response.getStatusLine().getStatusCode();
                HttpEntity responseEntity = response.getEntity();
                return new Pair<>(status, responseEntity.getContent());
            }
        }
    }

}
