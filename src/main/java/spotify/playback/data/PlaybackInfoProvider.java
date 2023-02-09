package spotify.playback.data;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import de.selbi.colorfetch.data.ColorFetchResult;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.enums.ModelObjectType;
import se.michaelthelin.spotify.exceptions.detailed.ForbiddenException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.special.PlaybackQueue;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Track;
import spotify.api.SpotifyApiException;
import spotify.api.SpotifyCall;
import spotify.api.events.SpotifyApiLoggedInEvent;
import spotify.playback.data.dto.PlaybackInfo;
import spotify.playback.data.dto.sub.CurrentlyPlaying;
import spotify.playback.data.dto.sub.PlaybackContext;
import spotify.playback.data.dto.sub.TrackData;
import spotify.playback.data.help.PlaybackInfoUtils;
import spotify.playback.data.visual.ContextProvider;
import spotify.playback.data.visual.artwork.ArtworkUrlProvider;
import spotify.playback.data.visual.color.ColorProviderSetup;
import spotify.util.BotUtils;

@Component
public class PlaybackInfoProvider {
  private static final int QUEUE_FALLBACK_THRESHOLD = 200;

  private final SpotifyApi spotifyApi;
  private final ContextProvider contextProvider;
  private final ArtworkUrlProvider artworkUrlProvider;
  private final ColorProviderSetup dominantColorProvider;

  private final Logger logger = Logger.getLogger(PlaybackInfoProvider.class.getName());

  private PlaybackInfo previous;
  private long deployTime;
  private boolean ready;

  private boolean queueEnabled;

  @Value("${server.port}")
  private String port;

  PlaybackInfoProvider(SpotifyApi spotifyApi,
      ContextProvider contextProvider,
      ArtworkUrlProvider artworkUrlProvider,
      ColorProviderSetup colorProvider) {
    this.spotifyApi = spotifyApi;
    this.contextProvider = contextProvider;
    this.artworkUrlProvider = artworkUrlProvider;
    this.dominantColorProvider = colorProvider;
    this.ready = false;
    this.queueEnabled = true;
    refreshDeployTime();
  }

  @EventListener(SpotifyApiLoggedInEvent.class)
  public void ready() {
    logger.info("SpotifyBigPicture is ready! URL: http://localhost:" + port);
    ready = true;
  }

  public void refreshDeployTime() {
    this.deployTime = System.currentTimeMillis();
  }

  public PlaybackInfo getCurrentPlaybackInfo(boolean full) {
    if (ready) {
      if (previous == null) {
        full = true;
      }
      try {
        CurrentlyPlayingContext currentlyPlayingContext = SpotifyCall.execute(spotifyApi.getInformationAboutUsersCurrentPlayback().additionalTypes("episode"));
        PlaybackQueue playbackQueue = null;
        if (queueEnabled) {
          try {
            playbackQueue = SpotifyCall.execute(spotifyApi.getTheUsersQueue());
            if (playbackQueue.getCurrentlyPlaying() == null) {
              // Edge case for local files
              PlaybackQueue.Builder builder = new PlaybackQueue.Builder();
              builder.setCurrentlyPlaying(currentlyPlayingContext.getItem());
              builder.setQueue(playbackQueue.getQueue());
              playbackQueue = builder.build();
            }
          } catch (SpotifyApiException e) {
            if (e.getNestedException().getClass().equals(ForbiddenException.class)) {
              queueEnabled = false;
              logger.warning("Queue has been disabled, as this feature is unavailable for free users!");
            }
          }
        }
        if (playbackQueue == null && currentlyPlayingContext != null) {
          playbackQueue = createFakePlaybackQueueForFreeUsers(currentlyPlayingContext);
        }
        if (playbackQueue != null && currentlyPlayingContext != null) {
          PlaybackInfo currentPlaybackInfo;
          ModelObjectType type = playbackQueue.getCurrentlyPlaying().getType();
          switch (type) {
            case TRACK:
              currentPlaybackInfo = buildInfoTrack(playbackQueue, currentlyPlayingContext);
              break;
            case EPISODE:
              currentPlaybackInfo = buildInfoEpisode(playbackQueue, currentlyPlayingContext);
              break;
            default:
              throw new IllegalStateException("Unknown ModelObjectType");
          }
          try {
            if (full) {
              return currentPlaybackInfo;
            } else {
              return getDifferences(currentPlaybackInfo);
            }
          } finally {
            this.previous = currentPlaybackInfo;
          }
        }
      } catch (SpotifyApiException e) {
        e.printStackTrace();
      }
    }
    return PlaybackInfo.EMPTY;
  }

  private PlaybackQueue createFakePlaybackQueueForFreeUsers(CurrentlyPlayingContext currentlyPlayingContext) {
    PlaybackQueue.Builder builder = new PlaybackQueue.Builder();
    builder.setCurrentlyPlaying(currentlyPlayingContext.getItem());
    builder.setQueue(List.of());
    return builder.build();
  }

  private PlaybackInfo getDifferences(PlaybackInfo current) {
    // Estimated progress always needs to get updated, so it's handled separately
    Integer previousTimeCurrent = previous.getCurrentlyPlaying().getTimeCurrent();
    Integer timeCurrent = current.getCurrentlyPlaying().getTimeCurrent();
    if (timeCurrent != null && previousTimeCurrent != null) {
      if (PlaybackInfoUtils.isWithinEstimatedProgressMs(previousTimeCurrent, timeCurrent)) {
        previous.getCurrentlyPlaying().setTimeCurrent(timeCurrent);
      }
    }

    return previous.equals(current) ? PlaybackInfo.EMPTY : current;
  }

  private PlaybackInfo buildBaseInfo(PlaybackQueue playbackQueue, CurrentlyPlayingContext context) {
    IPlaylistItem currentTrack = playbackQueue.getCurrentlyPlaying();

    // Meta data
    PlaybackInfo playbackInfo = new PlaybackInfo(PlaybackInfo.Type.DATA);
    playbackInfo.setDeployTime(deployTime);

    // CurrentlyPlaying
    CurrentlyPlaying currentlyPlaying = playbackInfo.getCurrentlyPlaying();
    currentlyPlaying.setId(currentTrack.getId());
    currentlyPlaying.setTimeCurrent(context.getProgress_ms());
    currentlyPlaying.setTimeTotal(currentTrack.getDurationMs());

    CurrentlyPlaying.ImageData imageData = currentlyPlaying.getImageData();
    String artworkUrl = artworkUrlProvider.findArtworkUrl(currentTrack);
    if (artworkUrl != null && !artworkUrl.isEmpty()) {
      imageData.setImageUrl(artworkUrl);
      ColorFetchResult colors = dominantColorProvider.getDominantColorFromImageUrl(artworkUrl);
      imageData.setImageColors(colors);
    }

    // PlaybackContext
    PlaybackContext playbackContext = playbackInfo.getPlaybackContext();
    playbackContext.setPaused(!context.getIs_playing());
    playbackContext.setShuffle(context.getShuffle_state());
    playbackContext.setRepeat(context.getRepeat_state());
    playbackContext.setVolume(context.getDevice().getVolume_percent());
    playbackContext.setContext(contextProvider.findContextName(context, previous));
    playbackContext.setDevice(context.getDevice().getName());

    // TrackData
    TrackData trackData = playbackInfo.getTrackData();
    trackData.setListTracks(List.of(TrackData.ListTrack.fromPlaylistItem(currentTrack)));
    trackData.setTrackNumber(1);
    trackData.setTrackCount(1);
    trackData.setTotalTime(1L);
    trackData.setDiscCount(1);
    trackData.setTrackListView(TrackData.ListViewType.QUEUE);
    ModelObjectType type = context.getContext() != null ? context.getContext().getType() : null;
    if (type == null) {
      type = ModelObjectType.GENRE; // generic fallback case
    }
    switch (type) {
      case ALBUM:
        // Album context
        trackData.setListTracks(contextProvider.getFormattedAlbumTracks());
        trackData.setTrackCount(contextProvider.getFormattedAlbumTracks().size());
        trackData.setTotalTime(contextProvider.getTotalTime(contextProvider.getFormattedAlbumTracks()));
        trackData.setTrackNumber(contextProvider.getCurrentlyPlayingAlbumTrackNumber());
        trackData.setDiscCount(contextProvider.getTotalDiscCount());
        if (!playbackContext.getContext().startsWith(ContextProvider.QUEUE_PREFIX)) {
          trackData.setTrackListView(TrackData.ListViewType.ALBUM);
        }
        break;
      case PLAYLIST:
        // Playlist context
        trackData.setListTracks(contextProvider.getFormattedPlaylistTracks());
        trackData.setTrackNumber(contextProvider.getCurrentlyPlayingPlaylistTrackNumber(context));
        trackData.setTrackCount(contextProvider.getFormattedPlaylistTracks().size());
        trackData.setTotalTime(contextProvider.getTotalTime(contextProvider.getFormattedPlaylistTracks()));
        trackData.setTrackListView(TrackData.ListViewType.PLAYLIST);
        break;
      case ARTIST:
        // Artist top tracks context
        trackData.setListTracks(contextProvider.getFormattedPlaylistTracks());
        trackData.setTrackNumber(contextProvider.getCurrentlyPlayingPlaylistTrackNumber(context));
        trackData.setTrackCount(contextProvider.getFormattedPlaylistTracks().size());
        trackData.setTotalTime(contextProvider.getTotalTime(contextProvider.getFormattedPlaylistTracks()));
        break;
      case EPISODE:
        // Podcast context
        trackData.setTrackListView(TrackData.ListViewType.PODCAST);
        break;
    }

    // Killswitch for gigantic playlists, to save performance
    if (playbackContext.getShuffle() || (trackData.getListTracks() != null && trackData.getListTracks().size() > QUEUE_FALLBACK_THRESHOLD)) {
      trackData.setTrackListView(TrackData.ListViewType.QUEUE);
      trackData.setListTracks(List.of());
    }

    List<TrackData.ListTrack> queue = playbackQueue.getQueue().stream()
        .map(TrackData.ListTrack::fromPlaylistItem)
        .collect(Collectors.toList());
    trackData.setQueue(queue);

    return playbackInfo;
  }

  private PlaybackInfo buildInfoTrack(PlaybackQueue playbackQueue, CurrentlyPlayingContext context) {
    PlaybackInfo pInfo = buildBaseInfo(playbackQueue, context);

    Track track = (Track) playbackQueue.getCurrentlyPlaying();
    CurrentlyPlaying currentlyPlaying = pInfo.getCurrentlyPlaying();

    currentlyPlaying.setArtists(BotUtils.toArtistNamesList(track.getArtists()));
    currentlyPlaying.setTitle(track.getName());
    currentlyPlaying.setAlbum(track.getAlbum().getName());
    currentlyPlaying.setYear(PlaybackInfoUtils.findReleaseYear(track));
    currentlyPlaying.setDescription("BLANK");

    return pInfo;
  }

  private PlaybackInfo buildInfoEpisode(PlaybackQueue playbackQueue, CurrentlyPlayingContext context) {
    PlaybackInfo pInfo = buildBaseInfo(playbackQueue, context);

    Episode episode = (Episode) playbackQueue.getCurrentlyPlaying();
    CurrentlyPlaying currentlyPlaying = pInfo.getCurrentlyPlaying();

    currentlyPlaying.setArtists(List.of(episode.getShow().getName()));
    currentlyPlaying.setTitle(episode.getName());
    currentlyPlaying.setAlbum(episode.getShow().getPublisher());
    currentlyPlaying.setDescription(episode.getDescription());
    currentlyPlaying.setYear(episode.getReleaseDate());

    return pInfo;
  }
}
