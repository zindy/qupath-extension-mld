package qupath.ext.mld;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.scene.control.MenuItem;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mld.ui.InterfaceController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.File;
import java.io.IOException;
import java.util.ResourceBundle;


/**
 * This is a demo to provide a mld for creating a new QuPath extension.
 * <p>
 * It doesn't do much - it just shows how to add a menu item and a preference.
 * See the code and comments below for more info.
 * <p>
 * <b>Important!</b> For your extension to work in QuPath, you need to make sure the name &amp; package
 * of this class is consistent with the file
 * <pre>
 *     /resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 * </pre>
 */
public class MldExtension implements QuPathExtension {
	// TODO: add and modify strings to this resource bundle as needed
	/**
	 * A resource bundle containing all the text used by the extension. This may be useful for translation to other languages.
	 * Note that this is optional and you can define the text within the code and FXML files that you use.
	 */
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mld.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(MldExtension.class);

	/**
	 * Display name for your extension
	 * TODO: define this
	 */
	private static final String EXTENSION_NAME = resources.getString("name");

	/**
	 * Short description, used under 'Extensions > Installed extensions'
	 * TODO: define this
	 */
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");

	/**
	 * QuPath version that the extension is designed to work with.
	 * This allows QuPath to inform the user if it seems to be incompatible.
	 * TODO: define this
	 */
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");

	/**
	 * Flag whether the extension is already installed (might not be needed... but we'll do it anyway)
	 */
	private boolean isInstalled = false;

	/**
	 * A 'persistent preference' - showing how to create a property that is stored whenever QuPath is closed.
	 * This preference will be managed in the main QuPath GUI preferences window.
	 */
    // Preference: Example of a setting you might want (e.g., whether to import Labels as Detections)
    private static final BooleanProperty importLabelsAsDetections = PathPrefs.createPersistentPreference(
            "mldImportLabelsAsDetections", true);

	/**
	 * Another 'persistent preference'.
	 * This one will be managed using a GUI element created by the extension.
	 * We use {@link Property<Integer>} rather than {@link IntegerProperty}
	 * because of the type of GUI element we use to manage it.
	 */
	private static final Property<Integer> integerOption = PathPrefs.createPersistentPreference(
			"demo.num.option", 1).asObject();

	/**
	 * An example of how to expose persistent preferences to other classes in your extension.
	 * @return The persistent preference, so that it can be read or set somewhere else.
	 */
	public static Property<Integer> integerOptionProperty() {
		return integerOption;
	}

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addPreferenceToPane(qupath);
		addMenuItem(qupath);
	}

	/**
	 * Mld showing how to add a persistent preference to the QuPath preferences pane.
	 * The preference will be in a section of the preference pane based on the
	 * category you set. The description is used as a tooltip.
	 * @param qupath The currently running QuPathGUI instance.
	 */

    private void addPreferenceToPane(QuPathGUI qupath) {
        var propertyItem = new PropertyItemBuilder<>(importLabelsAsDetections, Boolean.class)
                .name("Import Labels as Detections")
                .category("MLD Extension")
                .description("If true, Visiopharm 'Label' layers are imported as Detections. If false, as Annotations.")
                .build();
        
        qupath.getPreferencePane()
                .getPropertySheet()
                .getItems()
                .add(propertyItem);
    }

    private void importMldCurrentViewer() {
        try {
            var qupath = QuPathGUI.getInstance();
            var imageData = qupath.getImageData();
            if (imageData == null) {
                Dialogs.showErrorMessage("Import MLD", "No image currently open.");
                logger.warn("No image currently open.");
                return;
            }
            
            // Create file chooser
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select MLD File");
            fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("MLD Files", "*.mld"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            
            // Set initial directory to the image directory if possible
            try {
                var server = imageData.getServer();
                if (server != null) {
                    var uri = server.getURIs().iterator().next();
                    var imagePath = new File(uri.getPath());
                    var parentDir = imagePath.getParentFile();
                    if (parentDir != null && parentDir.exists()) {
                        fileChooser.setInitialDirectory(parentDir);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not set initial directory", e);
            }
            
            // Show dialog
            File selectedFile = fileChooser.showOpenDialog(qupath.getStage());
            
            if (selectedFile == null) {
                logger.info("MLD import cancelled by user.");
                return;
            }
            
            if (!selectedFile.exists()) {
                Dialogs.showErrorMessage("Import MLD", "Selected file does not exist.");
                logger.error("Selected file does not exist: {}", selectedFile);
                return;
            }
            
            boolean success = MldTools.readMLD(imageData, selectedFile);
            if (success) {
                logger.info("MLD Import successful from: {}", selectedFile.getAbsolutePath());
                Dialogs.showInfoNotification("Import MLD", "Successfully imported annotations from MLD file.");
                // Refresh the GUI hierarchy
                qupath.getViewer().getHierarchy().fireHierarchyChangedEvent(this);
            } else {
                Dialogs.showErrorMessage("Import MLD", "Failed to import MLD file.");
                logger.warn("MLD Import failed for file: {}", selectedFile.getAbsolutePath());
            }
            
        } catch (Exception e) {
            Dialogs.showErrorMessage("Import MLD", "Error importing MLD file: " + e.getMessage());
            logger.error("Unable to import MLD annotations", e);
        }
    }


	/**
	 * Mld showing how a new command can be added to a QuPath menu.
	 * @param qupath The QuPath GUI
	 */
    private void addMenuItem(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        
        MenuItem menuItem = new MenuItem("Import MLD annotations");
        menuItem.setOnAction(e -> importMldCurrentViewer());
        menu.getItems().add(menuItem);
        
        // Export intentionally omitted as MLD write support is not yet implemented
    }



	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}