import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import javazoom.jl.player.NullAudioDevice;
import support.PlayerWindow;
import support.Song;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

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

    private ReentrantLock lock = new ReentrantLock();

    // Estruturas que guardam informações das músicas na fila
    private ArrayList<String[]> musicArray = new ArrayList<>();
    private String[][] musicQueue;
    private  ArrayList<Song> reproductionQueue = new ArrayList<>();

    // Variáveis de estado
    private int playingState = 0;

    private boolean nextSong = false;
    private boolean previousSong = false;
    private boolean stopMusic = false;
    private boolean removeCurrentSong = false;

    // Auxiliares
    int skipTime;

    private Thread playerThread;
    private Thread scrubberUpdate;

    private final ActionListener buttonListenerPlayNow = e -> {
        if (playerThread != null) {
            playerThread.interrupt();
        }
        playerThread = new Thread(() -> {
            songIndex = this.window.getSelectedSongIndex();

            while (songIndex < reproductionQueue.size() && !playerThread.isInterrupted()) {
                lock.lock();
                try {
                    currentFrame = 0;
                    playingState = 1;
                } finally {
                    lock.unlock();
                }

                songPlaying = reproductionQueue.get(songIndex);

                closeObjects();
                startObjects();

                EventQueue.invokeLater(() -> {
                    this.window.setPlayPauseButtonIcon(playingState);
                    this.window.setEnabledPlayPauseButton(true);
                    this.window.setEnabledStopButton(true);
                    this.window.setEnabledScrubber(bitstream != null);
                    this.window.setEnabledPreviousButton((bitstream != null) && songIndex > 0);
                    this.window.setEnabledNextButton((bitstream != null) && songIndex < reproductionQueue.size() - 1);
                    this.window.setEnabledShuffleButton(false);
                    this.window.setEnabledLoopButton(false);
                    this.window.setPlayingSongInfo(songPlaying.getTitle(), songPlaying.getAlbum(), songPlaying.getArtist());
                });

                boolean continuePlaying = currentFrame < songPlaying.getNumFrames();
                while (continuePlaying && !playerThread.isInterrupted()) {
                    if (playingState == 1) {
                        lock.lock();
                        try {
                            continuePlaying = playNextFrame();
                            currentFrame++;
                        } catch (JavaLayerException ex) {
                            throw new RuntimeException(ex);
                        } finally {
                            lock.unlock();
                        }

                        EventQueue.invokeLater(() -> {
                            this.window.setTime((currentFrame * (int) songPlaying.getMsPerFrame()), (int) songPlaying.getMsLength());
                        });
                    }

                    // para a reprodução após algum input externo
                    if (stopMusic || removeCurrentSong || nextSong || previousSong) {
                        continuePlaying = false;
                    }
                }

                lock.lock();
                try {
                    if (stopMusic) {
                        songIndex = reproductionQueue.size();
                    } else if (nextSong) {
                        songIndex++;
                    } else if (previousSong) {
                        songIndex--;
                    } else if (!removeCurrentSong) {
                        songIndex++;
                    }

                    previousSong = false;
                    nextSong = false;
                    removeCurrentSong = false;
                    stopMusic = false;
                    playingState = 0;
                } finally {
                    lock.unlock();
                }

                // reset após a última música da lista de reprodução ser tocada
                if (songIndex >= reproductionQueue.size() || songIndex < 0) {
                    resetDisplayInfo();
                }
            }
            resetDisplayInfo();
        });
        playerThread.start();
    };

    private final ActionListener buttonListenerRemove = e -> {
        int removedSong = this.window.getSelectedSongIndex();

        if (removedSong == songIndex && playingState == 1) {      // remoção da música enquanto ela está sendo reproduzida
            if (songIndex == reproductionQueue.size() - 1) {      // remoção da última música da lista
                stopMusic = true;
            } else {
                removeCurrentSong = true;
            }
        } else if (songIndex > removedSong) {                      // arruma o index da música atual se uma música antes dela for removida
            songIndex--;
        }

        reproductionQueue.remove(removedSong);
        musicArray.remove(removedSong);
        musicQueue = musicArray.toArray(new String[0][]);

        this.window.setEnabledNextButton(playingState == 1 && songIndex < reproductionQueue.size() - 1);
        this.window.setEnabledPreviousButton(playingState == 1 && songIndex > 0);
        this.window.setQueueList(musicQueue);
    };

    private final ActionListener buttonListenerAddSong = e -> {
        Song newSong = this.window.openFileChooser();
        if (newSong != null) {
            musicArray.add(newSong.getDisplayInfo());
            musicQueue = musicArray.toArray(new String[0][]);
            reproductionQueue.add(newSong);

            this.window.setEnabledNextButton(playingState == 1 && songIndex < reproductionQueue.size() - 1);
            this.window.setEnabledPreviousButton(playingState == 1 && songIndex > 0);
            this.window.setQueueList(musicQueue);
        }

    };

    private final ActionListener buttonListenerPlayPause = e -> {
        switch (playingState) {
            case 0 -> playingState = 1;
            case 1 -> playingState = 0;
        }

        this.window.setPlayPauseButtonIcon(playingState);
    };

    private final ActionListener buttonListenerStop = e -> {
        stopMusic = true;
    };

    private final ActionListener buttonListenerNext = e -> {
        nextSong = true;
    };
    private final ActionListener buttonListenerPrevious = e -> {
        previousSong = true;
    };

    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};

    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
            scrubberUpdate = new Thread(() -> {
                EventQueue.invokeLater(() -> {
                    window.setTime(skipTime * (int) songPlaying.getMsPerFrame(), (int) songPlaying.getMsLength());
                });

                lock.lock();
                try {
                    if (skipTime < currentFrame) {
                        closeObjects();
                        startObjects();
                        currentFrame = 0;
                    }
                    skipToFrame(skipTime);
                } catch (BitstreamException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    lock.unlock();
                }

            });
            scrubberUpdate.start();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            skipTime = (int) (window.getScrubberValue() / songPlaying.getMsPerFrame());
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            skipTime = (int) (window.getScrubberValue() / songPlaying.getMsPerFrame());
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
        lock.lock();
        try {
            device = FactoryRegistry.systemRegistry().createAudioDevice();
            device.open(decoder = new Decoder());
            bitstream = new Bitstream(songPlaying.getBufferedInputStream());
        } catch (FileNotFoundException | JavaLayerException ignored) {

        } finally {
            lock.unlock();
        }
    }

    private void closeObjects () {
        lock.lock();
        try {
            if (bitstream != null) {
                bitstream.close();
                device.close();
            }
        } catch (BitstreamException ex) {
            throw new RuntimeException(ex);
        } finally {
            lock.unlock();
        }
    }

    private void resetDisplayInfo () {
        EventQueue.invokeLater(() -> {
            this.window.setPlayPauseButtonIcon(playingState);
            this.window.setEnabledPlayPauseButton(false);
            this.window.setEnabledStopButton(false);
            this.window.setEnabledScrubber(false);
            this.window.setEnabledPreviousButton(false);
            this.window.setEnabledNextButton(false);
            this.window.setEnabledShuffleButton(false);
            this.window.setEnabledLoopButton(false);
            this.window.resetMiniPlayer();
        });
    }


    //</editor-fold>
}