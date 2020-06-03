package se.terrassorkestern.notgen2.score;

import lombok.Data;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
public class Score {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne
    private Song song;
    @NotBlank(message = "Titel måste anges")
    private String title;
    private String subTitle;
    @NotBlank(message = "Genre måste anges")
    private String genre = "Foxtrot";
    private String composer;
    private String author;
    private String arranger;
    private Integer year = 1940;
    private String publisher;
    private String comment;
    private String internalComment;

    private String googleIdFull;
    private String googleIdTo;
    private String googleIdCover;
    private String googleIdThumbnail;

    @OneToMany(mappedBy = "score", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScorePart> scoreParts = new ArrayList<>();

    private Boolean scanned = true;
    private Boolean cover = true;
    private Boolean imageProcess = true;
    private Boolean upperleft = true;
    private Boolean color = true;

    private Boolean archived = true;
    private String archiveLocation = "A";

    private String filename;

    // Stuff to remove; first from DB then here
    private Boolean aktiv;
    private Boolean standard;
    private LocalDateTime inforskaffad;
    //@Transient
    private LocalDateTime updated;
    private String movie;
    private String orgText;
    @Column(name = "url_mp3_to")
    private String urlMp3To;
    private String urlMp3;
    private Integer betyg;
}
