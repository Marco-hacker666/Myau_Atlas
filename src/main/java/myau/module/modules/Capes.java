package myau.module.modules;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.client.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

public class Capes extends Module {
    public static final List<ResourceLocation> LOADED_CAPES = new ArrayList<>();
    public static String[] CAPES_NAME = getBuiltinCapes().toArray(new String[0]);

    public final ModeProperty capeMode = new ModeProperty("Cape", 0, CAPES_NAME);

    private static List<String> getBuiltinCapes() {
        List<String> capes = new ArrayList<>();
        try {
            java.net.URL url = Myau.class.getResource("/assets/myau/capes/");
            if (url != null) {
                if (url.getProtocol().equals("file")) {
                    File dir = new File(url.toURI());
                    if (dir.exists() && dir.listFiles() != null) {
                        for (File f : dir.listFiles()) {
                            if (f.getName().endsWith(".png")) {
                                capes.add(f.getName().replace(".png", ""));
                            }
                        }
                    }
                } else if (url.getProtocol().equals("jar")) {
                    String jarPath = url.getPath().substring(5, url.getPath().indexOf("!"));
                    try (java.util.jar.JarFile jar =
                                 new java.util.jar.JarFile(java.net.URLDecoder.decode(jarPath, "UTF-8"))) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith("assets/myau/capes/")
                                    && name.endsWith(".png")) {
                                String capeName = name.substring(name.lastIndexOf("/") + 1).replace(".png", "");
                                capes.add(capeName);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (capes.isEmpty()) {
            capes.add("anime");
        }
        return capes;
    }

    public final BooleanProperty allPlayer = new BooleanProperty("All player", false);
    public final BooleanProperty btnLoadCapes = new BooleanProperty("Load capes", false);
    public final BooleanProperty btnOpenFolder = new BooleanProperty("Open folder", false);

    private static File directory;

    public Capes() {
        super("Capes", false);

        directory =
                new File(Minecraft.getMinecraft().mcDataDir + File.separator + "keystrokes", "customCapes");
        if (!directory.exists()) {
            boolean success = directory.mkdirs();
            if (!success) {
                System.out.println("There was an issue creating customCapes directory.");
            }
        }

        loadCapes();
    }

    @Override
    public void verifyValue(String name) {
        if (name.equals("Load capes") && btnLoadCapes.getValue()) {
            btnLoadCapes.setValue(false);
            loadCapes();
        } else if (name.equals("Open folder") && btnOpenFolder.getValue()) {
            btnOpenFolder.setValue(false);
            try {
                Desktop.getDesktop().open(directory);
            } catch (IOException ex) {
                directory.mkdirs();
                ChatUtil.display("&cError locating folder, recreated.");
            }
        }
    }

    public void loadCapes() {
        final File[] files;
        try {
            files = Objects.requireNonNull(directory.listFiles());
        } catch (NullPointerException e) {
            ChatUtil.display("&cFail to load custom capes.");
            return;
        }

        final String[] builtinCapes = getBuiltinCapes().toArray(new String[0]);

        /* Names and textures are now added together, so index N of one is
           always index N of the other. The names used to be filled by file
           index while textures were only added on success, so one unreadable
           or non-.png file shifted every later cape onto the wrong name (and
           left null names in the list). ImageIO.read also returns null rather
           than throwing for content it cannot decode, and only IOException
           was caught -- that null went into DynamicTexture and threw, from the
           constructor on startup as well as from the reload button. */
        List<String> names = new ArrayList<>();
        LOADED_CAPES.clear();

        for (String s : builtinCapes) {
            String name = s.toLowerCase();
            try {
                InputStream stream =
                        Myau.class.getResourceAsStream("/assets/myau/capes/" + name + ".png");
                if (stream == null) {
                    stream =
                            Myau.class.getResourceAsStream("/assets/myau/capes/" + s + ".png");
                }
                if (stream == null) {
                    continue;
                }
                BufferedImage bufferedImage = ImageIO.read(stream);
                stream.close();
                if (bufferedImage == null) {
                    continue;
                }
                LOADED_CAPES.add(
                        Minecraft.getMinecraft()
                                .renderEngine
                                .getDynamicTextureLocation(name, new DynamicTexture(bufferedImage)));
                names.add(s);
            } catch (Exception e) {
                ChatUtil.display("&cFailed to load cape '&r" + s + "&c'");
            }
        }

        for (File file : files) {
            if (!file.exists() || !file.isFile()) continue;
            if (!file.getName().endsWith(".png")) continue;
            String fileName = file.getName().substring(0, file.getName().length() - 4);
            try {
                BufferedImage bufferedImage = ImageIO.read(file);
                if (bufferedImage == null) {
                    ChatUtil.display("&cNot a readable image: '&r" + file.getName() + "&c'");
                    continue;
                }
                LOADED_CAPES.add(
                        Minecraft.getMinecraft()
                                .renderEngine
                                .getDynamicTextureLocation(fileName, new DynamicTexture(bufferedImage)));
                names.add(fileName);
            } catch (Exception e) {
                ChatUtil.display("&cFailed to load cape '&r" + fileName + "&c'");
            }
        }

        if (names.isEmpty()) {
            names.add("none");
        }
        CAPES_NAME = names.toArray(new String[0]);

        capeMode.setModes(CAPES_NAME);
        ChatUtil.display("&aLoaded &r" + CAPES_NAME.length + "&a capes.");
    }

    public ResourceLocation getCape() {
        int index = capeMode.getValue();
        if (index >= 0 && index < LOADED_CAPES.size()) {
            return LOADED_CAPES.get(index);
        }
        return null;
    }
}