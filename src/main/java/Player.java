import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import javazoom.jl.player.NullAudioDevice;
import support.PlayerWindow;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;
    private int currentFrame = 0;
    private int songIndex;
    private Song songPlaying;

    // Estruturas que guardam informações das músicas na fila
    private ArrayList<String[]> musicArray = new ArrayList<>();
    private String[][] musicQueue;

    // Fila de músicas para a reprodução
    private  ArrayList<Song> reproductionQueue = new ArrayList<>();

    // Variavéis de estado
    private int playButtonState = 1;
    private boolean playing = false;

    private Thread playerThread;


    private final ActionListener buttonListenerPlayNow = e -> {
        if (bitstream != null) {
            playerThread.interrupt();
            closeObjects();
        }

        playerThread = new Thread (() -> {
            playButtonState = 1;
            playing = true;

            songIndex = this.window.getSelectedSongIndex();
            songPlaying = reproductionQueue.get(songIndex);
            this.window.setPlayingSongInfo(songPlaying.getTitle(), songPlaying.getAlbum(), songPlaying.getArtist());

            startObjects();

            boolean continuePlaying = true;
            while (continuePlaying && !playerThread.isInterrupted()) {
                if (playing) {
                    setBeginningButtons();
                    this.window.setTime((currentFrame * (int) songPlaying.getMsPerFrame()), (int) songPlaying.getMsLength());
                    try {
                        playNextFrame();
                    } catch (JavaLayerException ex) {
                        throw new RuntimeException(ex);
                    }
                    currentFrame++;
                }
                continuePlaying = currentFrame < songPlaying.getNumFrames();

                if (!continuePlaying) {
                    playerThread.interrupt();
                    closeObjects();
                    resetButtons();
                    playing = false;
                }
            }
            resetButtons();
        });

        playerThread.start();
    };

    private final ActionListener buttonListenerRemove = e -> {
        int removedSong = this.window.getSelectedSongIndex();

        if (removedSong == songIndex && playing) {
            playing = false;
            playerThread.interrupt();
            resetButtons();
        } else if (songIndex > removedSong) {
            // Arruma o lugar da música selecionada se uma música antes dela for removida
            songIndex--;
        }

        songIndex = (songIndex < 0) ? 0 : songIndex;

        reproductionQueue.remove(removedSong);
        musicArray.remove(removedSong);
        musicQueue = musicArray.toArray(new String[0][]);
        this.window.setQueueList(musicQueue);
    };

    private final ActionListener buttonListenerAddSong = e -> {
        Song newSong = this.window.openFileChooser();

        if (newSong != null) {
            musicArray.add(newSong.getDisplayInfo());
            musicQueue = musicArray.toArray(new String[0][]);
            reproductionQueue.add(newSong);
            this.window.setQueueList(musicQueue);
        }
    };

    private final ActionListener buttonListenerPlayPause = e -> {
        switch (playButtonState) {
            case 0:
                playButtonState = 1;
                playing = true;
                break;
            case 1:
                playButtonState = 0;
                playing = false;
                break;
        }
        this.window.setPlayPauseButtonIcon(playButtonState);
    };

    private final ActionListener buttonListenerStop = e -> {
        playing = false;
        playerThread.interrupt();
        resetButtons();
    };

    private final ActionListener buttonListenerNext = e -> {};
    private final ActionListener buttonListenerPrevious = e -> {};
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };

    public Player() {
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "Music Player",
                musicQueue,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">
    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        int framesToSkip = newFrame - currentFrame;
        boolean condition = true;
        while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
    }

    private void startObjects () {
        try {
            device = FactoryRegistry.systemRegistry().createAudioDevice();
            device.open(decoder = new Decoder());
            bitstream = new Bitstream(songPlaying.getBufferedInputStream());
        } catch (FileNotFoundException | JavaLayerException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void closeObjects () {
        currentFrame = 0;
        if (bitstream != null) {
            try {
                bitstream.close();
            } catch (BitstreamException ex) {
                throw new RuntimeException(ex);
            }
            device.close();
        }
    }

    private void setBeginningButtons () {
        this.window.setPlayPauseButtonIcon(playButtonState);
        this.window.setEnabledPlayPauseButton(true);
        this.window.setEnabledStopButton(true);
        this.window.setEnabledScrubber(false);
        this.window.setEnabledPreviousButton(false);
        this.window.setEnabledNextButton(false);
        this.window.setEnabledShuffleButton(false);
        this.window.setEnabledLoopButton(false);
    }

    private void resetButtons () {
        this.window.setPlayPauseButtonIcon(playButtonState);
        this.window.setEnabledPlayPauseButton(false);
        this.window.setEnabledStopButton(false);
        this.window.setEnabledScrubber(false);
        this.window.setEnabledPreviousButton(false);
        this.window.setEnabledNextButton(false);
        this.window.setEnabledShuffleButton(false);
        this.window.setEnabledLoopButton(false);
        this.window.resetMiniPlayer();
    }
    //</editor-fold>
}
