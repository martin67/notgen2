package se.terrassorkestern.notgen2.score;

import lombok.Data;

import javax.persistence.*;

@Data
@Entity
@Table(name = "imagedata")
public class ImageData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private int page;
    private String format;
    private int width;
    private int widthDpi;
    private int height;
    private int heightDpi;
    private int colorDepth;
    private String colorType;
}
