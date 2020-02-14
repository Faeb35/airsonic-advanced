package org.airsonic.player.ajax;

import org.airsonic.player.dao.MediaFileDao;
import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.domain.MusicFolder;
import org.airsonic.player.domain.Player;
import org.airsonic.player.domain.Playlist;
import org.airsonic.player.i18n.LocaleResolver;
import org.airsonic.player.service.MediaFileService;
import org.airsonic.player.service.PlayerService;
import org.airsonic.player.service.SecurityService;
import org.airsonic.player.service.SettingsService;
import org.directwebremoting.WebContextFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

@Controller
@MessageMapping("/playlists")
public class PlaylistWSController {
    @Autowired
    private MediaFileService mediaFileService;
    @Autowired
    private SecurityService securityService;
    @Autowired
    private org.airsonic.player.service.PlaylistService playlistService;
    @Autowired
    private MediaFileDao mediaFileDao;
    @Autowired
    private SettingsService settingsService;
    @Autowired
    private PlayerService playerService;
    @Autowired
    private LocaleResolver localeResolver;
    @Autowired
    private SimpMessagingTemplate brokerTemplate;

    @SubscribeMapping("/readable")
    public List<Playlist> getReadablePlaylists(Principal p) {
        return playlistService.getReadablePlaylistsForUser(p.getName());
    }

    @MessageMapping("/writable")
    public List<Playlist> getWritablePlaylists(Principal p) {
        return playlistService.getWritablePlaylistsForUser(p.getName());
    }

    public PlaylistInfo getPlaylist(int id) {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();

        Playlist playlist = playlistService.getPlaylist(id);
        List<MediaFile> files = playlistService.getFilesInPlaylist(id, true);

        String username = securityService.getCurrentUsername(request);
        mediaFileService.populateStarredDate(files, username);
        populateAccess(files, username);
        return new PlaylistInfo(playlist, createEntries(files));
    }

    private void populateAccess(List<MediaFile> files, String username) {
        for (MediaFile file : files) {
            if (!securityService.isFolderAccessAllowed(file, username)) {
                file.setPresent(false);
            }
        }
    }

    @MessageMapping("/create/empty")
    @SendToUser("/queue/playlists/added")
    public Playlist createEmptyPlaylist(Principal p) {
        Locale locale = localeResolver.resolveLocale(p.getName());
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale);

        Instant now = Instant.now();
        Playlist playlist = new Playlist();
        playlist.setUsername(p.getName());
        playlist.setCreated(now);
        playlist.setChanged(now);
        playlist.setShared(false);
        playlist.setName(dateFormat.format(now.atZone(ZoneId.systemDefault())));

        playlistService.createPlaylist(playlist);

        return playlist;
    }

    public int createPlaylistForPlayQueue() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = playerService.getPlayer(request, response);
        Locale locale = localeResolver.resolveLocale(request);
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale);

        Instant now = Instant.now();
        Playlist playlist = new Playlist();
        playlist.setUsername(securityService.getCurrentUsername(request));
        playlist.setCreated(now);
        playlist.setChanged(now);
        playlist.setShared(false);
        playlist.setName(dateFormat.format(now.atZone(ZoneId.systemDefault())));

        playlistService.createPlaylist(playlist);
        playlistService.setFilesInPlaylist(playlist.getId(), player.getPlayQueue().getFiles());

        return playlist.getId();
    }

    public int createPlaylistForStarredSongs() {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        Locale locale = localeResolver.resolveLocale(request);
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale);

        Instant now = Instant.now();
        Playlist playlist = new Playlist();
        String username = securityService.getCurrentUsername(request);
        playlist.setUsername(username);
        playlist.setCreated(now);
        playlist.setChanged(now);
        playlist.setShared(false);

        ResourceBundle bundle = ResourceBundle.getBundle("org.airsonic.player.i18n.ResourceBundle", locale);
        playlist.setName(bundle.getString("top.starred") + " " + dateFormat.format(now.atZone(ZoneId.systemDefault())));

        playlistService.createPlaylist(playlist);
        List<MusicFolder> musicFolders = settingsService.getMusicFoldersForUser(username);
        List<MediaFile> songs = mediaFileDao.getStarredFiles(0, Integer.MAX_VALUE, username, musicFolders);
        playlistService.setFilesInPlaylist(playlist.getId(), songs);

        return playlist.getId();
    }

    public void appendToPlaylist(int playlistId, List<Integer> mediaFileIds) {
        List<MediaFile> files = playlistService.getFilesInPlaylist(playlistId, true);
        for (Integer mediaFileId : mediaFileIds) {
            MediaFile file = mediaFileService.getMediaFile(mediaFileId);
            if (file != null) {
                files.add(file);
            }
        }
        playlistService.setFilesInPlaylist(playlistId, files);
    }

    private List<PlaylistInfo.Entry> createEntries(List<MediaFile> files) {
        List<PlaylistInfo.Entry> result = new ArrayList<PlaylistInfo.Entry>();
        for (MediaFile file : files) {
            result.add(new PlaylistInfo.Entry(file.getId(), file.getTitle(), file.getArtist(), file.getAlbumName(),
                    file.getDurationString(), file.getStarredDate() != null, file.isPresent()));
        }

        return result;
    }

    public PlaylistInfo toggleStar(int id, int index) {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        String username = securityService.getCurrentUsername(request);
        List<MediaFile> files = playlistService.getFilesInPlaylist(id, true);
        MediaFile file = files.get(index);

        boolean starred = mediaFileDao.getMediaFileStarredDate(file.getId(), username) != null;
        if (starred) {
            mediaFileDao.unstarMediaFile(file.getId(), username);
        } else {
            mediaFileDao.starMediaFile(file.getId(), username);
        }
        return getPlaylist(id);
    }

    public PlaylistInfo remove(int id, int index) {
        List<MediaFile> files = playlistService.getFilesInPlaylist(id, true);
        files.remove(index);
        playlistService.setFilesInPlaylist(id, files);
        return getPlaylist(id);
    }

    public PlaylistInfo up(int id, int index) {
        List<MediaFile> files = playlistService.getFilesInPlaylist(id, true);
        if (index > 0) {
            MediaFile file = files.remove(index);
            files.add(index - 1, file);
            playlistService.setFilesInPlaylist(id, files);
        }
        return getPlaylist(id);
    }

    public PlaylistInfo rearrange(int id, int[] indexes) {
        List<MediaFile> files = playlistService.getFilesInPlaylist(id, true);
        MediaFile[] newFiles = new MediaFile[files.size()];
        for (int i = 0; i < indexes.length; i++) {
            newFiles[i] = files.get(indexes[i]);
        }
        playlistService.setFilesInPlaylist(id, Arrays.asList(newFiles));
        return getPlaylist(id);
    }

    public PlaylistInfo down(int id, int index) {
        List<MediaFile> files = playlistService.getFilesInPlaylist(id, true);
        if (index < files.size() - 1) {
            MediaFile file = files.remove(index);
            files.add(index + 1, file);
            playlistService.setFilesInPlaylist(id, files);
        }
        return getPlaylist(id);
    }

    public void deletePlaylist(int id) {
        playlistService.deletePlaylist(id);
    }

    public PlaylistInfo updatePlaylist(int id, String name, String comment, boolean shared) {
        Playlist playlist = playlistService.getPlaylist(id);
        playlist.setName(name);
        playlist.setComment(comment);
        playlist.setShared(shared);
        playlistService.updatePlaylist(playlist);
        return getPlaylist(id);
    }

    public void setPlaylistService(org.airsonic.player.service.PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    public void setSecurityService(SecurityService securityService) {
        this.securityService = securityService;
    }

    public void setMediaFileService(MediaFileService mediaFileService) {
        this.mediaFileService = mediaFileService;
    }

    public void setMediaFileDao(MediaFileDao mediaFileDao) {
        this.mediaFileDao = mediaFileDao;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setPlayerService(PlayerService playerService) {
        this.playerService = playerService;
    }

    public void setLocaleResolver(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }
}
