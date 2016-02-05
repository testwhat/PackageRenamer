/**
 *  Copyright 2015 R.H.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package pkgrenamer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.im.InputContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import org.apache.commons.io.IOUtils;

import brut.androlib.Androlib;
import brut.androlib.AndrolibException;
import brut.androlib.ApkDecoder;
import brut.androlib.ApkOptions;
import brut.androlib.res.data.ResPackage;
import brut.androlib.res.data.ResTable;
import brut.androlib.res.decoder.AXmlResourceParser;
import brut.androlib.res.decoder.ResAttrDecoder;
import brut.androlib.res.decoder.XmlPullStreamDecoder;
import brut.androlib.res.util.ExtFile;
import brut.androlib.res.util.ExtMXSerializer;
import brut.common.BrutException;
import brut.directory.DirectoryException;

import com.android.signapk.SignApk;

public class Main {
    static final String WORKING_DIR = System.getProperty("user.dir");

    public static void main(String[] args) {
        new UI().show();
    }

    static class LiteApkDecoder {
        final Androlib androlib;
        final ExtFile mApkFile;
        ResTable mResTable;

        LiteApkDecoder(File apkFile, String frameworkPath) {
            ApkOptions apkOptions = new ApkOptions();
            apkOptions.frameworkFolderLocation = frameworkPath;
            androlib = new Androlib(apkOptions);
            mApkFile = new ExtFile(apkFile);
            try {
                mResTable = androlib.getResTable(mApkFile, true);
            } catch (AndrolibException e) {
                e.printStackTrace();
                mResTable = new ResTable();
            }
        }

        String getManifest(boolean decodeResource) {
            AXmlResourceParser axmlParser = new AXmlResourceParser();
            ResAttrDecoder resDecoder = new ResAttrDecoder();

            final Logger logger = Logger.getLogger(AXmlResourceParser.class.getName());
            final Level origLevel = logger.getLevel();
            final ResTable resTable;
            if (decodeResource) {
                resTable = mResTable;
            } else {
                resTable = new ResTable();
                logger.setLevel(Level.OFF);
            }

            resDecoder.setCurrentPackage(new ResPackage(resTable, 0, null));
            axmlParser.setAttrDecoder(resDecoder);
            final String encoding = "utf-8";
            ExtMXSerializer serial = new ExtMXSerializer();
            serial.setProperty(
                    "http://xmlpull.org/v1/doc/properties.html#serializer-indentation",
                    "    ");
            serial.setProperty(
                    "http://xmlpull.org/v1/doc/properties.html#serializer-line-separator",
                    System.getProperty("line.separator"));
            serial.setProperty("DEFAULT_ENCODING", encoding);
            serial.setDisabledAttrEscape(true);

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            try {
                PrintStream ps = new PrintStream(os, true, encoding);
                XmlPullStreamDecoder xmlDecoder = new XmlPullStreamDecoder(axmlParser, serial);
                xmlDecoder.decode(mApkFile.getDirectory().getFileInput("AndroidManifest.xml"), ps);
            } catch (AndrolibException | DirectoryException | UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            logger.setLevel(origLevel);
            return os.toString();
        }
    }

    static File unpack(String apkName) {
        ApkDecoder decoder = new ApkDecoder();
        decoder.setForceDelete(true);
        try {
            final short DECODE_SOURCES_NONE = 0x0000;
            decoder.setDecodeSources(DECODE_SOURCES_NONE);
        } catch (AndrolibException e) {
            e.printStackTrace();
            return null;
        }
        String outName = apkName;
        outName = outName.endsWith(".apk") ?
                outName.substring(0, outName.length() - 4) : outName + ".out";

        outName = new File(outName).getName();
        File outDir = new File(outName);
        try {
            decoder.setOutDir(outDir);
        } catch (AndrolibException e) {
            e.printStackTrace();
            return null;
        }
        decoder.setFrameworkDir(WORKING_DIR);
        decoder.setApkFile(new File(apkName));
        try {
            decoder.decode();
        } catch (AndrolibException | DirectoryException | IOException e) {
            e.printStackTrace();
            return null;
        }
        return outDir;
    }

    static void replaceKeyword(File outDir, int num, String appName) {
        final Charset charset = StandardCharsets.UTF_8;
        Path path = new File(outDir, "AndroidManifest.xml").toPath();
        try {
            String content = new String(Files.readAllBytes(path), charset);
            content = content.replaceFirst(
                    "package=\"([a-zA-Z.]+)(\\d*)\"",
                    "package=\"$1" + num + "\"");
            content = content.replaceAll("\\d*\\.permission.C2D_MESSAGE",
                    num + ".permission.C2D_MESSAGE");
            Files.write(path, content.getBytes(charset));

            path = new File(outDir, "res/values/strings.xml").toPath();
            content = new String(Files.readAllBytes(path), charset);
            String newAppName = "$1" + num;
            if (appName != null && appName.trim().length() > 0) {
                newAppName = appName + num;
            }
            content = content.replaceFirst(
                    "<string name=\"app_name\">([\\w\\p{InCJKUnifiedIdeographs}]+)",
                    "<string name=\"app_name\">" + newAppName);
            Files.write(path, content.getBytes(charset));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static File build(File outDir, File originalApk) {
        ApkOptions apkOptions = new ApkOptions();
        try {
            new Androlib(apkOptions).build(outDir, null);
        } catch (BrutException e) {
            e.printStackTrace();
        }
        return new File(new File(outDir, "dist"), originalApk.getName());
    }

    public static String path(String... path) {
        StringBuilder sb = new StringBuilder(64);
        int last = path.length - 1;
        for (int i = 0; i < last; i++) {
            sb.append(path[i]).append(File.separator);
        }
        sb.append(path[last]);
        return sb.toString();
    }

    public static String getFilenamePrefix(String filename) {
        int dotPos = filename.lastIndexOf('.');
        if (dotPos == -1) {
            return filename;
        }
        return filename.substring(0, dotPos);
    }

    public static String getFilenameSuffix(String filename) {
        int dotPos = filename.lastIndexOf('.');
        if (dotPos == -1) {
            return "";
        }
        return filename.substring(dotPos + 1);
    }

    public static String getFileDirPath(String path) {
        return path.substring(0, path.lastIndexOf(java.io.File.separatorChar) + 1);
    }

    public static File appendTail(File f, String str) {
        String name = getFilenamePrefix(f.getName())
                + str + "." + getFilenameSuffix(f.getName());
        return new File(getFileDirPath(f.getAbsolutePath()), name);
    }

    static class UI {
        JDesktopPane mDesktop;
        JFrame mFrame;
        int mInnerCount = 0;
        JProgressBar mProgressBar;
        JTextArea mInfo;
        IntTextField mStartNumber;
        IntTextField mAmount;
        JTextField mCustomizeAppName;

        public UI() {
            try {
                UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
            } catch (ClassNotFoundException | InstantiationException
                    | IllegalAccessException | UnsupportedLookAndFeelException e) {
                e.printStackTrace();
            }
            Font f = new Font("Dialog", Font.PLAIN, 12);
            FontUIResource fontRes = new FontUIResource(f);
            for (Enumeration<?> keys = UIManager.getDefaults().keys(); keys.hasMoreElements();) {
                Object key = keys.nextElement();
                Object value = UIManager.get(key);
                if (value instanceof FontUIResource) {
                    UIManager.put(key, fontRes);
                }
            }

            mFrame = new JFrame("Package Renamer v0.1 [共存產生器] by 低調不具名");
            mFrame.setLocationByPlatform(true);

            mDesktop = new Droppable.Desktop() {
                @Override
                public void onDrop(final File[] files) {
                    run(files);
                }
            };
            mDesktop.setSize(420, 265);
            mFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            mFrame.setResizable(false);

            Container c = mFrame.getContentPane();
            c.setLayout(new BorderLayout());
            c.add(mDesktop, BorderLayout.CENTER);

            final int leftPop = 15;
            mFrame.setSize(mDesktop.getSize());
            JLabel l = newLabel("Drag apk file to here [把apk檔拖曳到這裡]");
            l.setBounds(leftPop, 10, 250, 30);
            mDesktop.add(l);

            final JLabel pb = new JLabel("<html>Powered by <u>apktool</u></html>");
            pb.setForeground(Color.CYAN);
            pb.setCursor(new Cursor(Cursor.HAND_CURSOR));
            pb.setBounds(285, 10, 110, 30);
            pb.setToolTipText("http://ibotpeaches.github.io/Apktool/");
            pb.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    try {
                        Desktop.getDesktop().browse(new URI(pb.getToolTipText()));
                    } catch (URISyntaxException | IOException ex) {
                    }
                }
            });
            mDesktop.add(pb);

            mProgressBar = new JProgressBar(JProgressBar.HORIZONTAL);
            mProgressBar.setIndeterminate(true);
            mProgressBar.setBounds(leftPop - 2, 38, mDesktop.getWidth() - 38, 10);
            mProgressBar.setVisible(false);
            mDesktop.add(mProgressBar);

            mInfo = new Droppable.TextArea() {
                @Override
                public void onDrop(File[] files) {
                    run(files);
                }
            };
            mInfo.setEditable(false);

            JScrollPane sp = new JScrollPane(mInfo);
            sp.setBounds(10, 125, mDesktop.getWidth() - 26, 100);
            mDesktop.add(sp);

            JLabel snL = newLabel("Start number [起始編號]");
            snL.setBounds(leftPop, 50, 130, 30);
            mDesktop.add(snL);
            mStartNumber = new IntTextField(5);
            mStartNumber.setBounds(145, 50, 60, 30);
            mStartNumber.setText("2");
            mDesktop.add(mStartNumber);

            JLabel nL = newLabel("Amount [產生幾個]");
            nL.setBounds(230, 50, 130, 30);
            mDesktop.add(nL);
            mAmount = new IntTextField(5);
            mAmount.setBounds(335, 50, 60, 30);
            mAmount.setText("1");
            mDesktop.add(mAmount);

            JLabel anL = newLabel("New name (allow empty) [自訂應用程式名稱, 可不填]");
            anL.setBounds(leftPop, 85, 290, 30);
            mDesktop.add(anL);
            mCustomizeAppName = new JTextField(10);
            mCustomizeAppName.setBounds(305, 85, 90, 30);
            mDesktop.add(mCustomizeAppName);
        }

        void show() {
            mFrame.setVisible(true);
            appendInfo("This program is for study purpose only. Use at own risk.\n"
                    + "本程式僅供研究參考. 風險自行承擔.\n"
                    + "Not every application can apply this. Do not expect it will work.\n"
                    + "不是每個應用程式都適用. 請不要過度期待.");
        }

        JLabel newLabel(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(Color.WHITE);
            return l;
        }

        void appendInfo(final String text) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        appendInfo(text);
                    }
                });
                return;
            }
            mInfo.append(text + "\n");
            mInfo.setCaretPosition(mInfo.getDocument().getLength());
        }

        void run(final File[] files) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    if (mProgressBar.isVisible()) {
                        return null;
                    }
                    mProgressBar.setVisible(true);
                    if (files.length > 0) {
                        processApk(files[0]);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    mProgressBar.setVisible(false);
                }
            }.execute();
        }

        void processApk(final File file) {
            boolean foundManifest = false;
            try (ZipFile zip = new ZipFile(file)) {
                foundManifest = zip.getEntry("AndroidManifest.xml") != null;
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!foundManifest) {
                appendInfo("Not valid input file. [無法識別輸入檔案] " + file);
                return;
            }
            prepareKey();

            appendInfo("Unpacking apk... [解apk中...]");
            File outDir = unpack(file.getAbsolutePath());
            if (outDir == null) {
                appendInfo("Failed to unpack [解失敗了]");
                return;
            }

            final int amount = mAmount.getValue();
            final int start = mStartNumber.getValue();
            final String cAppName = mCustomizeAppName.getText();
            final int n = start + amount;
            for (int i = mStartNumber.getValue(); i < n; i++) {
                appendInfo("Replacing and building... #" + i
                        + " [建置中... 編號 " + i + "]");
                replaceKeyword(outDir, i, cAppName);
                File distApk = build(outDir, file);
                appendInfo("Signing... " + distApk + " [簽章中...]");
                if (distApk.exists()) {
                    String finalOutput = appendTail(file, "_m" + i).getAbsolutePath();
                    SignApk.main(new String[] {
                            path(WORKING_DIR, "testkey.x509.pem"),
                            path(WORKING_DIR, "testkey.pk8"),
                            distApk.getAbsolutePath(),
                            finalOutput
                    });
                    String progress = "(" + (i - start + 1) + "/" + amount + ")";
                    appendInfo("Done " + finalOutput + " " + progress + " "
                            + " [完成 " + progress + "]");
                } else {
                    appendInfo("Failed to build [建置失敗] " + outDir);
                }
            }
        }
    }

    static void prepareKey() {
        final String[] keyFiles = { "testkey.x509.pem", "testkey.pk8" };
        for (String k : keyFiles) {
            File kf = new File(WORKING_DIR, k);
            if (!kf.exists()) {
                try (InputStream in = Main.class.getResourceAsStream("/"
                        + Main.class.getPackage().getName() + "/" + k)) {
                    try (OutputStream out = new FileOutputStream(kf)) {
                        IOUtils.copy(in, out);
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }
}

class IntTextField extends JTextField {
    InputContext mInputContext;

    IntTextField(int size) {
        super("", size);
        mInputContext = InputContext.getInstance();
        mInputContext.selectInputMethod(java.util.Locale.ENGLISH);
    }

    @Override
    protected Document createDefaultModel() {
        return new IntTextDocument();
    }

    @Override
    public InputContext getInputContext() {
        return mInputContext;
    }

    @Override
    public boolean isValid() {
        try {
            Integer.parseInt(getText());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    int getValue() {
        try {
            return Integer.parseInt(getText());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static class IntTextDocument extends PlainDocument {

        @Override
        public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
            int len = str.length(), p = 0;
            char[] sb = new char[len];
            for (int i = 0; i < len; i++) {
                char c = str.charAt(i);
                if (c >= '0' && c <= '9') {
                    sb[p++] = c;
                }
            }
            str = p == 0 ? "" : new String(sb, 0, p);
            super.insertString(offs, str, a);
        }
    }
}

class Droppable {
    final static int DRAG_OPERATION = System.getProperty("os.name").contains("Windows")
            ? DnDConstants.ACTION_MOVE
            : DnDConstants.ACTION_COPY_OR_MOVE;

    interface CanDropFile {
        void onDrop(File[] files);
    }

    static class FileDropListener implements DropTargetListener {
        final CanDropFile mOwner;

        public FileDropListener(CanDropFile drop) {
            mOwner = drop;
        }

        @Override
        public void dragEnter(DropTargetDragEvent dtde) {
            dtde.acceptDrag(DRAG_OPERATION);
        }

        @Override
        public void dragOver(DropTargetDragEvent dtde) {
        }

        @Override
        public void dropActionChanged(DropTargetDragEvent dtde) {
        }

        @Override
        public void dragExit(DropTargetEvent dte) {
        }

        @Override
        public void drop(DropTargetDropEvent dtde) {
            onDropFiles(dtde, this);
        }

        public void onDropFile(File[] files) {
            mOwner.onDrop(files);
        }
    }

    abstract static class TextArea extends JTextArea implements CanDropFile {
        TextArea() {
            new DropTarget(this, new FileDropListener(this));
        }
    }

    abstract static class Desktop extends JDesktopPane implements CanDropFile {
        public Desktop() {
            new DropTarget(this, new FileDropListener(this));
        }
    }

    static DataFlavor sNixFileDataFlavor;

    static void onDropFiles(DropTargetDropEvent dtde, FileDropListener onDrop) {
        try {
            Transferable transferable = dtde.getTransferable();

            if (transferable
                    .isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrop(DnDConstants.ACTION_MOVE);
                java.util.List<?> files = (java.util.List<?>) transferable
                        .getTransferData(DataFlavor.javaFileListFlavor);
                File[] fa = new File[files.size()];
                for (int i = 0; i < fa.length; i++) {
                    fa[i] = (File) files.get(i);
                }
                onDrop.onDropFile(fa);
                dtde.getDropTargetContext().dropComplete(true);

            } else {
                if (sNixFileDataFlavor == null) {
                    sNixFileDataFlavor = new DataFlavor(
                            "text/uri-list;class=java.lang.String");
                }
                dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
                String data = (String) transferable
                        .getTransferData(sNixFileDataFlavor);
                if (data != null) {
                    ArrayList<File> fs = new ArrayList<>();
                    for (StringTokenizer st = new StringTokenizer(data, "\r\n"); st
                            .hasMoreTokens();) {
                        String token = st.nextToken().trim();
                        if (token.startsWith("#") || token.isEmpty()) {
                            continue;
                        }
                        try {
                            fs.add(new File(new URI(token)));
                        } catch (Exception e) {
                        }
                    }
                    onDrop.onDropFile(fs.toArray(new File[0]));
                    dtde.getDropTargetContext().dropComplete(true);
                } else {
                    dtde.rejectDrop();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
