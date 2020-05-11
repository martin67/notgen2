package se.terrassorkestern.notgen2.playlist;

import lombok.Data;
import lombok.EqualsAndHashCode;
import se.terrassorkestern.notgen2.Auditable;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Entity
public class Playlist extends Auditable<String> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @NotBlank(message = "Låtlistan måste ha ett namn")
    private String name;
    private String comment;
    private LocalDate date;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "playlist_id", nullable = false)
    @OrderBy("sort_order")
    List<PlaylistEntry> playlistEntries = new ArrayList<>();


    Playlist copy() {
        Playlist newPlaylist = new Playlist();

        newPlaylist.setName("Kopia av " + this.getName());
        newPlaylist.setDate(this.getDate());
        newPlaylist.setComment(this.getComment());
        for (PlaylistEntry playlistEntry : this.getPlaylistEntries()) {
            PlaylistEntry newPlaylistEntry = new PlaylistEntry();
            newPlaylistEntry.setText(playlistEntry.getText());
            newPlaylistEntry.setBold(playlistEntry.getBold());
            newPlaylistEntry.setComment(playlistEntry.getComment());
            newPlaylistEntry.setSortOrder(playlistEntry.getSortOrder());
            newPlaylist.getPlaylistEntries().add(newPlaylistEntry);
        }
        return newPlaylist;
    }
}
