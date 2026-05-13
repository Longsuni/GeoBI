/*
 * Datart
 * <p>
 * Copyright 2021
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package datart.core.common;

import datart.core.base.exception.Exceptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.net.URL;

@Slf4j
public class WebUtils {

    private static final Integer DEFAULT_TIMEOUT = 60;

    private static final Object CHROME_LOCK = new Object();

    private static volatile WebDriver sharedWebDriver;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(WebUtils::shutdownSharedWebDriver, "datart-webdriver-shutdown"));
    }

    private static boolean isReuseWebDriver() {
        return Boolean.parseBoolean(Application.getProperty("datart.screenshot.reuse-webdriver", "true"));
    }

    private static void shutdownSharedWebDriver() {
        synchronized (CHROME_LOCK) {
            quitSharedDriverLocked();
        }
    }

    private static void quitSharedDriverLocked() {
        if (sharedWebDriver != null) {
            try {
                sharedWebDriver.quit();
            } catch (Exception e) {
                log.warn("quit shared WebDriver: {}", e.getMessage());
            }
            sharedWebDriver = null;
        }
    }

    private static WebDriver createWebDriver() throws Exception {

        String driverPath = Application.getProperty("datart.screenshot.webdriver-path");

        if (StringUtils.isEmpty(driverPath)) {
            Exceptions.msg("message.not.found.webdriver");
        }

        String driverType = Application.getProperty("datart.screenshot.webdriver-type");

        switch (driverType) {
            case "CHROME":
                return createChromeWebDriver(driverPath);
            default:
                Exceptions.msg("message.unsupported.webdriver", driverType);
        }
        return null;
    }

    public static <T> T screenShot(String url, OutputType<T> outputType, int imageWidth) throws Exception {
        if (isReuseWebDriver()) {
            synchronized (CHROME_LOCK) {
                if (sharedWebDriver == null) {
                    log.info("Creating shared headless Chrome WebDriver for screenshots");
                    sharedWebDriver = createWebDriver();
                }
                try {
                    return doScreenShot(sharedWebDriver, url, outputType, imageWidth);
                } catch (Exception e) {
                    log.warn("Screenshot failed, discarding shared WebDriver: {}", e.getMessage());
                    quitSharedDriverLocked();
                    Exceptions.e(e);
                }
            }
        }
        WebDriver webDriver = createWebDriver();
        try {
            return doScreenShot(webDriver, url, outputType, imageWidth);
        } finally {
            try {
                webDriver.quit();
            } catch (Exception e) {
                log.warn("quit WebDriver: {}", e.getMessage());
            }
        }
    }

    private static <T> T doScreenShot(WebDriver webDriver, String url, OutputType<T> outputType, int imageWidth)
            throws Exception {
        webDriver.get(url);

        WebDriverWait wait = new WebDriverWait(webDriver, getTimeout().longValue());

        ExpectedCondition<WebElement> conditionOfSign =
                ExpectedConditions.presenceOfElementLocated(By.id("headlessBrowserRenderSign"));
        ExpectedCondition<WebElement> conditionOfWidth =
                ExpectedConditions.presenceOfElementLocated(By.id("width"));
        ExpectedCondition<WebElement> conditionOfHeight =
                ExpectedConditions.presenceOfElementLocated(By.id("height"));
        wait.until(ExpectedConditions.and(conditionOfSign, conditionOfWidth, conditionOfHeight));

        Double contentWidth = Double.parseDouble(webDriver.findElement(By.id("width")).getAttribute("value"));

        Double contentHeight = Double.parseDouble(webDriver.findElement(By.id("height")).getAttribute("value"));

        if (imageWidth > 0 && imageWidth != contentWidth) {
            webDriver.manage().window().setSize(new Dimension(imageWidth, contentHeight.intValue()));
        }
        sleepStabilizeMs("datart.screenshot.stabilize-ms-after-layout", 400);
        contentWidth = Double.parseDouble(webDriver.findElement(By.id("width")).getAttribute("value"));
        contentWidth = contentWidth > 0 ? contentWidth : 1920;
        contentHeight = Double.parseDouble(webDriver.findElement(By.id("height")).getAttribute("value"));
        contentHeight = contentHeight > 0 ? contentHeight : 600;
        webDriver.manage().window().setSize(new Dimension(contentWidth.intValue(), contentHeight.intValue()));
        sleepStabilizeMs("datart.screenshot.stabilize-ms-before-capture", 300);

        TakesScreenshot screenshot = (TakesScreenshot) webDriver;
        return screenshot.getScreenshotAs(outputType);
    }

    private static void sleepStabilizeMs(String propertyKey, int defaultMs) throws InterruptedException {
        int ms = Integer.parseInt(Application.getProperty(propertyKey, Integer.toString(defaultMs)));
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    public static File screenShot2File(String url, String path, int imageWidth) throws Exception {

        File temp = screenShot(url, OutputType.FILE, imageWidth);
        path = FileUtils.concatPath(path, temp.getName());
        File file = new File(path);

        FileUtils.delete(file);

        FileUtils.mkdirParentIfNotExist(path);

        FileCopyUtils.copy(temp, file);

        FileUtils.delete(temp);

        return file;
    }

    public static Integer getTimeout() {
        return Integer.parseInt(Application.getProperty("datart.screenshot.timeout-seconds", DEFAULT_TIMEOUT.toString()));
    }

    private static WebDriver createChromeWebDriver(String driverPath) throws Exception {

        ChromeOptions options = new ChromeOptions();
        String chromeBinary = Application.getProperty("datart.screenshot.chrome-binary-path");
        if (StringUtils.isNotBlank(chromeBinary)) {
            options.setBinary(chromeBinary.trim());
        }
        options.addArguments("headless=new");
        options.addArguments("no-sandbox");
        options.addArguments("disable-gpu");
        options.addArguments("disable-features=NetworkService");
        options.addArguments("ignore-certificate-errors");
        options.addArguments("silent-launch");
        options.addArguments("disable-application-cache");
        options.addArguments("disable-web-security");
        options.addArguments("no-proxy-server");
        options.addArguments("disable-dev-shm-usage");
        options.addArguments("window-size=2048,1536");

        if (isRemoteDriver(driverPath)) {
            return new RemoteWebDriver(new URL(driverPath), options);
        }

        System.setProperty(ChromeDriverService.CHROME_DRIVER_EXE_PROPERTY, driverPath);

        return new ChromeDriver(options);
    }

    private static boolean isRemoteDriver(String driverPath) {
        return driverPath.startsWith("http");
    }
}
