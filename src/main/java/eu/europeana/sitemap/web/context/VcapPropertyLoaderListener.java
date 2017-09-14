package eu.europeana.sitemap.web.context;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.StandardServletEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Created by ymamakis on 11/16/15.
 */

public class VcapPropertyLoaderListener implements
        ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger LOG = LogManager.getLogger(VcapPropertyLoaderListener.class);

    private final static String SWIFT_AUTHENTICATION_URL="vcap.services.swift-sitemap.credentials.authentication_uri";
    private final static String SWIFT_AUTHENTICATION_AV_ZONE="vcap.services.swift-sitemap.credentials.availability_zone";
    private final static String SWIFT_AUTHENTICATION_TENANT_NAME="vcap.services.swift-sitemap.credentials.tenant_name";
    private final static String SWIFT_AUTHENTICATION_USER_NAME="vcap.services.swift-sitemap.credentials.user_name";
    private final static String SWIFT_AUTHENTICATION_PASSWORD="vcap.services.swift-sitemap.credentials.password";
    private static final String VCAP_APPLICATION = "VCAP_APPLICATION";

    private static final String VCAP_SERVICES = "VCAP_SERVICES";

    // Before ConfigFileApplicationListener so values there can use these ones
    private int order = ConfigFileApplicationListener.DEFAULT_ORDER - 1;

    private final JsonParser parser = JsonParserFactory.getJsonParser();

    private static StandardServletEnvironment env = new StandardServletEnvironment();

    public VcapPropertyLoaderListener() {
        LOG.info("Creating VcapPropertyLoaderListener()");
        this.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(new SpringApplication(), new String[0], env));
        ClassLoader c = Thread.currentThread().getContextClassLoader();
        @SuppressWarnings("resource")
        URLClassLoader urlC = (URLClassLoader) c;
        URL[] urls = urlC.getURLs();
        String path = urls[0].getPath();
        Properties props = new Properties();

        File sitemapProperties = new File(path + "/sitemap.properties");
        try (FileInputStream fin = new FileInputStream(sitemapProperties)){
            props.load(fin);

            if (env.getProperty(SWIFT_AUTHENTICATION_URL) != null) {
                props.setProperty("swift.authUrl", env.getProperty(SWIFT_AUTHENTICATION_URL));
                props.setProperty("swift.password", env.getProperty(SWIFT_AUTHENTICATION_PASSWORD));
                props.setProperty("swift.username", env.getProperty(SWIFT_AUTHENTICATION_USER_NAME));
                props.setProperty("swift.regionName", env.getProperty(SWIFT_AUTHENTICATION_AV_ZONE));
                props.setProperty("swift.tenantName", env.getProperty(SWIFT_AUTHENTICATION_TENANT_NAME));
                props.setProperty("swift.containerName", env.getProperty("sitemap"));
            }

            // Write the Properties into the sitemap.properties
            // Using the built-in store() method escapes certain characters (e.g. '=' and ':'), which is
            // not what we want to do (it breaks reading the properties elsewhere)
            // While we're writing the properties manually, might as well sort the list alphabetically...
            List<String> sortedKeys = new ArrayList<String>();
            for (Object key : props.keySet()) {
                sortedKeys.add(key.toString());
            }
            Collections.sort(sortedKeys);

            StringBuilder sb = new StringBuilder(256);
            sb.append("#Generated by the VCAPPropertyLoaderListener" + "\n");
            sb.append("#").append(new Date()).append("\n");
            LOG.info("Writing properties to file...");
            for (String key : sortedKeys) {
                LOG.info("  {} = {}", key, props.getProperty(key).toString());
                sb.append(key).append("=").append(props.getProperty(key)).append("\n");
            }
            // Overwriting the original file
            FileUtils.writeStringToFile(sitemapProperties, sb + "\n", false);

        } catch (IOException e) {
            LOG.error("Error reading properties file", e);
        }

    }




        public void setOrder(int order) {
            this.order = order;
        }

        @Override
        public int getOrder() {
            return this.order;
        }

        @Override
        public final void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            LOG.info("New application event! {}", event);
            ConfigurableEnvironment environment = event.getEnvironment();
            if (!environment.containsProperty(VCAP_APPLICATION)
                    && !environment.containsProperty(VCAP_SERVICES)) {
                return;
            }
            Properties properties = new Properties();
            addWithPrefix(properties, getPropertiesFromApplication(environment),
                    "vcap.application.");
            addWithPrefix(properties, getPropertiesFromServices(environment),
                    "vcap.services.");
            LOG.info("Properties:");
            for (String propName : properties.stringPropertyNames()) {
                LOG.info("  {} = {}", propName, properties.get(propName));
            }
            MutablePropertySources propertySources = environment.getPropertySources();
            if (propertySources
                    .contains(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME)) {
                propertySources.addAfter(
                        CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
                        new PropertiesPropertySource("vcap", properties));
            }
            else {
                propertySources.addFirst(new PropertiesPropertySource("vcap", properties));
            }
            LOG.info("System environment:");
            for (String propName : environment.getSystemEnvironment().keySet()) {
                LOG.info("  {} = {}", propName, environment.getSystemEnvironment().get(propName));
            }
            LOG.info("System properties:");
            for (String propName : environment.getSystemProperties().keySet()) {
                LOG.info("{} = {}", propName, environment.getSystemProperties().get(propName));
            }
        }

        private void addWithPrefix(Properties properties, Properties other, String prefix) {
            Enumeration propertynames = other.propertyNames();
           while (propertynames.hasMoreElements()) {
               String key = propertynames.nextElement().toString();
                String prefixed = prefix + key;
               if(other.getProperty(key)!=null) {
                   properties.setProperty(prefixed, other.getProperty(key));
               } else {
                   System.out.println(key);
               }
            }
        }

        private Properties getPropertiesFromApplication(Environment environment) {
            Properties properties = new Properties();
            try {
                Map<String, Object> map = this.parser.parseMap(environment.getProperty(
                        VCAP_APPLICATION, "{}"));
                extractPropertiesFromApplication(properties, map);
            }
            catch (Exception e) {
               LOG.error("Error reading properties from application", e);
            }
            return properties;
        }

        private Properties getPropertiesFromServices(Environment environment) {
            Properties properties = new Properties();
            try {
                Map<String, Object> map = this.parser.parseMap(environment.getProperty(
                        VCAP_SERVICES, "{}"));
                extractPropertiesFromServices(properties, map);
            }
            catch (Exception e) {
                LOG.error("Error reading properties from services", e);
            }
            return properties;
        }

        private void extractPropertiesFromApplication(Properties properties,
                                                      Map<String, Object> map) {
            if (map != null) {
                flatten(properties, map, "");
            }
        }

        private void extractPropertiesFromServices(Properties properties,
                                                   Map<String, Object> map) {
            if (map != null) {
                for (Object services : map.values()) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) services;
                    for (Object object : list) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> service = (Map<String, Object>) object;
                        String key = (String) service.get("name");
                        if (key == null) {
                            key = (String) service.get("label");
                        }
                        flatten(properties, service, key);
                    }
                }
            }
        }

        private void flatten(Properties properties, Map<String, Object> input, String path) {
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                String key = entry.getKey();
                if (StringUtils.hasText(path)) {
                    if (key.startsWith("[")) {
                        key = path + key;
                    }
                    else {
                        key = path + "." + key;
                    }
                }
                Object value = entry.getValue();

                if (value instanceof String) {

                    properties.put(key, value);
                }
                else if (value instanceof Map) {
                    // Need a compound key
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) value;

                    flatten(properties, map, key);
                }
                else if (value instanceof Collection) {
                    // Need a compound key
                    @SuppressWarnings("unchecked")
                    Collection<Object> collection = (Collection<Object>) value;
                    properties.put(key,
                            StringUtils.collectionToCommaDelimitedString(collection));
                    int count = 0;

                    for (Object object : collection) {
                        flatten(properties,
                                Collections.singletonMap("[" + (count++) + "]", object), key);
                    }
                }
                else {
                    System.out.println("is else " +key+":" +value);
                    properties.put(key, value == null ? "" : value);
                }
            }
        }


}
