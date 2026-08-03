package chat.ui;

import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

public final class ChatApplication extends Application {
    private static ChatApplication instance;
    private JavaScriptBridge bridge;

    @Override
    public void start(Stage stage) {
        instance = this;
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        FrontendNotifier notifier = new FrontendNotifier(engine);
        Path downloads = Path.of("data", "downloads");
        bridge = new JavaScriptBridge(notifier, stage, downloads);

        URL page = ChatApplication.class.getResource("/web/index.html");
        if (page == null) {
            throw new IllegalStateException("Frontend não encontrado. Execute o build em frontend/");
        }
        engine.getLoadWorker().stateProperty().addListener((observable, oldState, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaBridge", bridge);
                Defaults defaults = readDefaults(getParameters().getRaw());
                notifier.setDefaults(defaults.host, defaults.port, defaults.username);
            }
        });
        engine.load(page.toExternalForm());

        stage.setTitle("Conecta - Chat TCP");
        stage.setScene(new Scene(webView, 1180, 760));
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.show();
    }

    private Defaults readDefaults(List<String> args) {
        String host = option(args, "--host", "127.0.0.1");
        String username = option(args, "--username", "");
        int port;
        try {
            port = Integer.parseInt(option(args, "--port", "5000"));
        } catch (NumberFormatException error) {
            port = 5000;
        }
        return new Defaults(host, port, username);
    }

    private String option(List<String> args, String name, String fallback) {
        int index = args.indexOf(name);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback;
    }

    public static void openDocument(String uri) {
        if (instance != null) {
            instance.getHostServices().showDocument(uri);
        }
    }

    @Override
    public void stop() {
        if (bridge != null) {
            bridge.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record Defaults(String host, int port, String username) {
    }
}
