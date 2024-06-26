package de.stylelabor.dev.playercountryinfovelocity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.SuffixNode;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;



@Plugin(
        id = "playercountryinfo-velocity",
        name = "PlayerCountryInfo-Velocity",
        version = "1"
)
public class PlayerCountryInfo_Velocity {

    @SuppressWarnings("unused")
    @Inject
    private Logger logger;

    @Inject
    private ProxyServer server;

    private final Map<UUID, String> playerCountryCodes = new HashMap<>();

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Copy the data-country.json file to the PlayerCountryInfo directory
        File pluginDir = new File("plugins/PlayerCountryInfo");
        if (!pluginDir.exists()) {
            pluginDir.mkdirs();
        }
        File jsonFile = new File(pluginDir, "data-country.json");
        if (!jsonFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/data-country.json")) {
                Files.copy(Objects.requireNonNull(in), Paths.get(jsonFile.getPath()));
            } catch (IOException e) {
                logger.error("Failed to copy data-country.json", e);
            }
        }

        // Load the players.yml file
        File file = new File("plugins/PlayerCountryInfo", "players.yml");
        Yaml yaml = new Yaml();
        Map<String, Object> data;

        try {
            if (file.exists()) {
                InputStream inputStream = new FileInputStream(file);
                data = yaml.load(inputStream);

                // Load the data into the playerCountryCodes map
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    UUID playerUUID = UUID.fromString(entry.getKey());
                    Map<String, String> playerData = (Map<String, String>) entry.getValue();
                    String countryCode = playerData.get("CountryCode");
                    playerCountryCodes.put(playerUUID, countryCode);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load players.yml", e);
        }
    }

    private Map<String, String> createPlayerData(String country, String city, String ip, String countryCode) {
        Map<String, String> playerData = new HashMap<>();
         playerData.put("Country", country);
        // playerData.put("City", city);
        // playerData.put("IP", ip);
        playerData.put("CountryCode", countryCode);
        return playerData;
    }

    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Retrieve the country code from the map
        String countryCode = playerCountryCodes.getOrDefault(playerUUID, "XX");

        // Get the LuckPerms API
        LuckPerms api = LuckPermsProvider.get();

        // Get the LuckPerms User for the player
        User user = api.getUserManager().getUser(player.getUniqueId());

        if (user != null) {
            // Remove old suffix nodes
            user.data().clear(node -> node instanceof SuffixNode);

            // Get the server the player is currently on
            String serverName = event.getServer().getServerInfo().getName();

            // Create a suffix node with a space before the country code and server name
            SuffixNode suffixNode = SuffixNode.builder(" [" + countryCode + "] | &3" + serverName, 100).build();

            // Add the new suffix node to the user
            user.data().add(suffixNode);

            // Save the user data
            api.getUserManager().saveUser(user);
        }
    }


    private Map<String, String> loadCountryCodes() {
        Map<String, String> countryCodes = new HashMap<>();
        File jsonFile = new File("plugins/PlayerCountryInfo", "data-country.json");
        try {
            JsonElement jsonElement = new Gson().fromJson(new FileReader(jsonFile), JsonElement.class);
            if (jsonElement.isJsonArray()) {
                for (JsonElement element : jsonElement.getAsJsonArray()) {
                    JsonObject jsonObject = element.getAsJsonObject();
                    String countryName = jsonObject.get("Name").getAsString();
                    String countryCode = jsonObject.get("Code").getAsString();
                    countryCodes.put(countryName, countryCode);
                }
            }
        } catch (FileNotFoundException e) {
            logger.error("Failed to load data-country.json", e);
        }
        return countryCodes;
    }

    private void scheduleTask(Player player, String countryCode, long delay, TimeUnit unit) {
        // Create effectively final variables
        final Player finalPlayer = player;
        final String finalCountryCode = countryCode;
        final User finalUser = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        final LuckPerms finalApi = LuckPermsProvider.get();

        // Schedule a task to be executed after a delay
        server.getScheduler().buildTask(this, () -> {
            // Get the server the finalPlayer is currently on
            String serverName = finalPlayer.getCurrentServer().map(serverConnection -> serverConnection.getServerInfo().getName()).orElse("Unknown");

            // Create a suffix node with a space before the finalCountryCode and server name
            SuffixNode suffixNode = SuffixNode.builder(" [" + finalCountryCode + "] | &3" + serverName, 100).build();

            // Add the new suffix node to the finalUser
            Objects.requireNonNull(finalUser).data().add(suffixNode);

            // Save the finalUser data
            finalApi.getUserManager().saveUser(finalUser);
        }).delay(delay, unit).schedule();
    }

    @Subscribe
    public void onPlayerLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        // Save this information in the players.yml file
        File pluginDir = new File("plugins/PlayerCountryInfo");
        if (!pluginDir.exists() && !pluginDir.mkdirs()) {
            logger.error("Failed to create directory {}", pluginDir.getPath());
            return;
        }
        File file = new File(pluginDir, "players.yml");
        Yaml yaml = new Yaml();
        Map<String, Object> data;

        try {
            if (file.exists()) {
                InputStream inputStream = new FileInputStream(file);
                data = yaml.load(inputStream);
            } else {
                data = new HashMap<>();
            }
            // Check if player's UUID already exists in the file
            if (data.containsKey(player.getUniqueId().toString())) {
                // Player already exists, so skip the API call
                return;
            }

            String country = "";
            String city = "";
            String countryCode = "";

            // Check if the IP address is localhost
            if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")) {
                // Player is connecting from localhost, so set the country code to "XX"
                country = "Localhost";
                city = "Localhost";
                countryCode = "XX";
            } else {
                // Player is not connecting from localhost, so make the API call
                URL url = new URL("https://api.hackertarget.com/ipgeo/?q=" + ip);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                InputStream responseStream = connection.getInputStream();
                Scanner scanner = new Scanner(new InputStreamReader(responseStream));
                String response = scanner.useDelimiter("\\A").next();

                // Try to parse the response as JSON
                try {
                    Gson gson = new Gson();
                    JsonReader reader = new JsonReader(new StringReader(response));
                    reader.setLenient(true);
                    JsonElement jsonElement = gson.fromJson(reader, JsonElement.class);

                    if (jsonElement.isJsonObject()) {
                        JsonObject jsonObject = jsonElement.getAsJsonObject();
                        country = jsonObject.get("country").getAsString();
                        city = jsonObject.get("city").getAsString();
                        countryCode = jsonObject.get("countryCode").getAsString();
                    } else {
                        throw new JsonParseException("Not a JSON Object");
                    }
                } catch (JsonParseException e) {
                    // If the response is not JSON, try to parse it as plain text
                    String[] lines = response.split("\n");
                    for (String line : lines) {
                        String[] parts = line.split(": ");
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();

                            switch (key) {
                                case "Country":
                                    country = value;
                                    break;
                                case "City":
                                    city = value;
                                    break;
                                case "Country Code":
                                    countryCode = value;
                                    break;
                            }
                        }
                    }
                }
            }

            // Load country codes from data-country.json
            Map<String, String> countryCodes = loadCountryCodes();
            countryCode = countryCodes.getOrDefault(country, "XX");

            // Debug: Print the country and country code
            logger.info("Country: {}", country);
            logger.info("Country Code: {}", countryCode);

            Map<String, String> playerData = createPlayerData(country, city, ip, countryCode);

            // Add the current system time to the playerData map
            playerData.put("LastJoinTime", String.valueOf(System.currentTimeMillis()));

            data.put(player.getUniqueId().toString(), playerData);

            FileWriter writer = new FileWriter(file);
            yaml.dump(data, writer);

            // Store the country code in the map
            playerCountryCodes.put(playerUUID, countryCode);


            // Get the LuckPerms API
            LuckPerms api = LuckPermsProvider.get();

            // Get the LuckPerms User for the player
            User user = api.getUserManager().getUser(player.getUniqueId());

            if (user != null) {
                // Remove old suffix nodes
                user.data().clear(node -> node instanceof SuffixNode);

                // Get the server the player is currently on
                String serverName = player.getCurrentServer().map(serverConnection -> serverConnection.getServerInfo().getName()).orElse("Unknown");

                // Create a suffix node with a space before the country code and server name
                SuffixNode suffixNode = SuffixNode.builder(" [" + countryCode + "] | &3" + serverName, 100).build();

                // Add the new suffix node to the user
                user.data().add(suffixNode);

                // Save the user data
                api.getUserManager().saveUser(user);
            }



        } catch (IOException e) {
            logger.error("Failed to make API call", e);
        }
    }
}