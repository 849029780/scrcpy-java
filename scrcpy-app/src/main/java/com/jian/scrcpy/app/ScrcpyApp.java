package com.jian.scrcpy.app;
import javafx.application.Application;import javafx.scene.*;import javafx.scene.layout.*;import javafx.stage.Stage;
public final class ScrcpyApp extends Application{
 public void start(Stage stage){VideoView view=new VideoView();StackPane root=new StackPane(view);view.fitWidthProperty().bind(root.widthProperty());view.fitHeightProperty().bind(root.heightProperty());stage.setTitle("scrcpy-java");stage.setScene(new Scene(root,480,800));stage.show();}
 public static void main(String[] args){launch(args);}
}