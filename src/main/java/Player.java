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
import java.util.Collections;
import java.util.Random;
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
    private ArrayList<String[]> songInfo = new ArrayList<>();
    private  ArrayList<Song> reproductionQueue = new ArrayList<>();

    // Variáveis de estado
    private int playingState = 0;

    private boolean nextSong = false;
    private boolean previousSong = false;
    private boolean stopMusic = false;
    private boolean removeCurrentSong = false;
    private boolean shuffle = false;
    private boolean loop = false;
    private boolean updatingScrubberDrag = false;

    // Auxiliares
    int skipTime;
    long randomSeed;    // variável usada para randomizacao da lista de reproducao

    // Copias das listas originais de reproducao e de uuid
    private  ArrayList<Song> unshuffledReproductionQueue = new ArrayList<>();
    private ArrayList<String[]> unshuffledSongInfo = new ArrayList<>();

    private Thread playerThread;
    private Thread updateShuffledList;
    private Thread updateScrubber;


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

                lock.lock();
                try {
                    closeObjects();
                    startObjects();
                } finally {
                    lock.unlock();
                }

                EventQueue.invokeLater(() -> {
                    this.window.setPlayPauseButtonIcon(playingState);
                    this.window.setEnabledPlayPauseButton(true);
                    this.window.setEnabledStopButton(true);
                    this.window.setEnabledScrubber(bitstream != null);
                    this.window.setEnabledPreviousButton((bitstream != null) && (songIndex > 0 || loop));
                    this.window.setEnabledNextButton((bitstream != null) && (songIndex < reproductionQueue.size() - 1 || loop));
                    this.window.setEnabledShuffleButton(!reproductionQueue.isEmpty());
                    this.window.setEnabledLoopButton(!reproductionQueue.isEmpty());
                    this.window.setPlayingSongInfo(songPlaying.getTitle(), songPlaying.getAlbum(), songPlaying.getArtist());
                });

                boolean continuePlaying = currentFrame < songPlaying.getNumFrames();
                while (continuePlaying && !playerThread.isInterrupted()) {
                    if (playingState == 1) {
                        lock.lock();
                        try {
                            continuePlaying = playNextFrame();
                            currentFrame++;

                            // para a reprodução após algum input externo
                            if (stopMusic || removeCurrentSong || nextSong || previousSong) {
                                continuePlaying = false;
                            }
                        } catch (JavaLayerException ex) {
                            throw new RuntimeException(ex);
                        } finally {
                            lock.unlock();
                        }
                        EventQueue.invokeLater(() -> {
                            this.window.setTime((currentFrame * (int) songPlaying.getMsPerFrame()), (int) songPlaying.getMsLength());
                        });
                    }
                }

                lock.lock();
                try {
                    if (stopMusic) {
                        songIndex = reproductionQueue.size() + 1;
                    } else if (nextSong) {
                        if (loop && songIndex == reproductionQueue.size() - 1) {
                            songIndex = 0;
                        } else {
                            songIndex++;
                        }
                    } else if (previousSong) {
                        if (loop && songIndex == 0) {
                            songIndex = reproductionQueue.size() - 1;
                        } else {
                            songIndex--;
                        }
                    } else if (!removeCurrentSong) {
                        songIndex++;
                    }

                    previousSong = false;
                    nextSong = false;
                    removeCurrentSong = false;
                    stopMusic = false;
                    playingState = 0;

                    if (loop && songIndex == reproductionQueue.size()) {
                        songIndex = 0;
                    }
                } finally {
                    lock.unlock();
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

        unshuffledReproductionQueue.remove(reproductionQueue.get(removedSong));
        unshuffledSongInfo.remove(songInfo.get(removedSong));

        reproductionQueue.remove(removedSong);
        songInfo.remove(removedSong);

        this.window.setEnabledNextButton(playingState == 1 && (songIndex < reproductionQueue.size() - 1 || loop));
        this.window.setEnabledPreviousButton(playingState == 1 && (songIndex > 0 || loop));
        this.window.setQueueList(songInfo.toArray(new String[0][])); // utiliza songInfo convertida de ArrayList para Array
        this.window.setEnabledShuffleButton(!reproductionQueue.isEmpty());
        this.window.setEnabledLoopButton(!reproductionQueue.isEmpty());
    };

    private final ActionListener buttonListenerAddSong = e -> {
        Song newSong = this.window.openFileChooser();
        if (newSong != null) {
            songInfo.add(newSong.getDisplayInfo());
            reproductionQueue.add(newSong);

            unshuffledReproductionQueue.add(newSong);
            unshuffledSongInfo.add(newSong.getDisplayInfo());

            this.window.setEnabledNextButton(playingState == 1 && (songIndex < reproductionQueue.size() - 1 || loop));
            this.window.setEnabledPreviousButton(playingState == 1 && (songIndex > 0 || loop));
            this.window.setQueueList(songInfo.toArray(new String[0][]));
            this.window.setEnabledShuffleButton(!reproductionQueue.isEmpty());
            this.window.setEnabledLoopButton(!reproductionQueue.isEmpty());
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

    private final ActionListener buttonListenerShuffle = e -> {
        updateShuffledList = new Thread(() -> {
            if (shuffle) {
                lock.lock();
                try {
                    songIndex = unshuffledReproductionQueue.indexOf(reproductionQueue.get(songIndex));

                    reproductionQueue.clear();
                    songInfo.clear();

                    reproductionQueue.addAll(unshuffledReproductionQueue);
                    songInfo.addAll(unshuffledSongInfo);
                } finally {
                    lock.unlock();
                }
                shuffle = false;
            } else {
                randomSeed = System.nanoTime();        // numero aleatorio

                lock.lock();
                try {
                    unshuffledReproductionQueue.clear();
                    unshuffledSongInfo.clear();

                    unshuffledReproductionQueue.addAll(reproductionQueue);
                    unshuffledSongInfo.addAll(songInfo);

                    if (playingState == 1) {
                        // remove a musica atual da lista de reproducao e, logo em seguida, a torna a primeira musica da lista em modo aleatorio
                        reproductionQueue.remove(songIndex);
                        songInfo.remove(songIndex);

                        // randomiza lista de reproducao e lista de uuid com o mesmo numero aleatorio para que elas continuem sempre iguais
                        Collections.shuffle(reproductionQueue, new Random(randomSeed));
                        Collections.shuffle(songInfo, new Random(randomSeed));

                        reproductionQueue.add(0, unshuffledReproductionQueue.get(songIndex));
                        songInfo.add(0, unshuffledSongInfo.get(songIndex));
                    } else {
                        Collections.shuffle(reproductionQueue, new Random(randomSeed));
                        Collections.shuffle(songInfo, new Random(randomSeed));
                    }
                    songIndex = 0;
                } finally {
                    lock.unlock();
                }
                shuffle = true;
            }

            EventQueue.invokeLater(() -> {
                this.window.setQueueList(songInfo.toArray(new String[0][]));
                this.window.setEnabledNextButton(bitstream != null && (songIndex < reproductionQueue.size() - 1 || loop));
                this.window.setEnabledPreviousButton(bitstream != null && (songIndex > 0 || loop));
            });
        });
        updateShuffledList.start();
    };

    private final ActionListener buttonListenerLoop = e -> {
        loop = !loop;
        this.window.setEnabledPreviousButton(playingState == 1 && (songIndex > 0 || loop));
        this.window.setEnabledNextButton(playingState == 1 && (songIndex < reproductionQueue.size() - 1 || loop));
        this.window.setEnabledLoopButton(!reproductionQueue.isEmpty());
    };

    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
            updateScrubber = new Thread(() -> {
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
                    currentFrame = skipTime;
                } catch (BitstreamException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    lock.unlock();
                }

            });
            updateScrubber.start();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            skipTime = (int) (window.getScrubberValue() / songPlaying.getMsPerFrame());
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            skipTime = (int) (window.getScrubberValue() / songPlaying.getMsPerFrame());
            updateScrubber = new Thread(() -> {
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
                    currentFrame = skipTime;
                } catch (BitstreamException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    lock.unlock();
                }
            });
            updateScrubber.start();
        }
    };

    public Player() {
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "Music Player",
                songInfo.toArray(new String[0][]),
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
        } catch (FileNotFoundException | JavaLayerException ignored) {

        }
    }

    private void closeObjects () {
        try {
            if (bitstream != null) {
                bitstream.close();
                device.close();
            }
        } catch (BitstreamException ex) {
            throw new RuntimeException(ex);
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
            this.window.setEnabledShuffleButton(!reproductionQueue.isEmpty());
            this.window.setEnabledLoopButton(!reproductionQueue.isEmpty());
            this.window.resetMiniPlayer();
        });
    }




    //</editor-fold>
}