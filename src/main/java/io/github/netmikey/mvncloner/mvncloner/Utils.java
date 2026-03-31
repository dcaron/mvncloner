package io.github.netmikey.mvncloner.mvncloner;

import java.net.http.HttpRequest.Builder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;

/**
 * @author mike
 */
public class Utils {

    /**
     * Set an authentication header with the specified credentials on the
     * HttpRequest Builder if username and password are not <code>null</code>.
     */
    public static Builder setCredentials(Builder requestBuilder, String username, String password) {
        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            requestBuilder.setHeader("Authorization", authorizationHeaderValue(username, password));
        }
        return requestBuilder;
    }

    /**
     * Build a Basic Authorization header value from the specified credentials.
     */
    static String authorizationHeaderValue(String username, String password) {
        byte[] usernameAndPasswordBytes = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        String base64encodedUsernameAndPassword = Base64.getEncoder().encodeToString(usernameAndPasswordBytes);
        return "Basic " + base64encodedUsernameAndPassword;
    }

    /**
     * Failsafe sleep.
     *
     * @param millis
     *            the length of time to sleep in milliseconds.
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
