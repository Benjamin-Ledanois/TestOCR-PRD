package com.example.imageclassify;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.SparseArray;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.imageclassify.ml.Detect;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.tensorflow.lite.support.image.TensorImage;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    Button selectBtn, predictBtn, captureBtn, copyBtn;       // Les différents boutons de l'interface
    TextView result;                                // TextView pour afficher le résultat
    TextView scoreText;                             // TextView pour afficher le score (detection d'objet seulement
    TextView locationText;                          // TextView pour afficher la localisation de l'objet (detection d'objet seulement)
    Bitmap bitmap;                                  // Bitmap pour stocker l'image
    ImageView imageView;                            // ImageView pour afficher l'image
    Spinner spinner;                                // Spinner pour choisir le modèle de détection
    ArrayAdapter<String> adapter;                    // Adapter pour remplir le spinner
    SharedPreferences preferences;                    // SharedPreferences pour stocker le modèle choisi
    SharedPreferences.Editor editor;                  // Editor pour modifier les préférences
    String selectedModel;                           // Modèle choisi
    String [] models = {"Google Vision",
                        "ML Kit",
                        "TFLite (objet)"};          // Modèles disponibles
    ClipboardManager clipboard;                     // ClipboardManager pour copier le texte du textView de résultat
    ClipData clip;                                  // ClipData pour stocker le texte à copier

    // Launcher pour la demande de permission pour utiliser la camera du téléphone
    private final ActivityResultLauncher<String> requestCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    takePicture();
                } else {
                    Toast.makeText(this, "Vous devez autoriser !", Toast.LENGTH_SHORT).show();
                }
            });

    // Launcher pour choisir une image depuis le telephone
    private final ActivityResultLauncher<Intent> chooseImageActivityLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->{
                    Intent data = result.getData();
                    if (data != null){
                        Uri uri = data.getData();
                        try {
                            bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
                            // On pivote l'image de 90°
                            Matrix matrix = new Matrix();
                            matrix.postRotate(90);
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                            imageView.setImageBitmap(bitmap);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            );

    // Launcher pour prendre une photo avec la camera du téléphone
    private final ActivityResultLauncher<Intent> takePictureActivityLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                Intent data = result.getData();
                    if (data != null){
                        bitmap = (Bitmap) data.getExtras().get("data");
                        // On pivote l'image de 90°
                        Matrix matrix = new Matrix();
                        matrix.postRotate(90);
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        imageView.setImageBitmap(bitmap);
                    }
                }
            );


    /**
     * Méthode appelée à la création de l'activité
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in {@link #onSaveInstanceState}.  <b><i>Note: Otherwise it is null.</i></b>
     *
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // On récupère les éléments de l'interface
        selectBtn = findViewById(R.id.selectBtn);
        captureBtn = findViewById(R.id.captureBtn);
        predictBtn = findViewById(R.id.predictBtn);
        copyBtn = findViewById(R.id.copyBtn);
        result = findViewById(R.id.result);
        imageView = findViewById(R.id.imageView);
        //scoreText = findViewById(R.id.score);
        //locationText = findViewById(R.id.location);

        // Listener sur le bouton pour choisir une image depuis la galerie
        selectBtn.setOnClickListener(v -> chooseImage());

        // Listener sur le bouton pour copier le texte dans le presse-papier
        copyBtn.setOnClickListener(v -> copyText());

        // Listener sur le bouton pour prendre une photo avec la camera du téléphone
        captureBtn.setOnClickListener(v -> {
            // On vérifie que l'on a bien la permission pour la caméra du téléphone
            if(checkPermission(Manifest.permission.CAMERA)) {
                takePicture();
            // Si non on la demande
            }else{
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        // On récupère les preférences de l'application
        preferences = getPreferences(MODE_PRIVATE);
        editor = preferences.edit();

        // On récupère le spinner et on le remplit avec l'adapter
        spinner = findViewById(R.id.spinner);
        adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item
        );
        spinner.setAdapter(adapter);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapter.addAll(models);

        // On récupère le modèle choisi dans les préférences et on modifie le spinner en fonction
        selectedModel = preferences.getString(String.valueOf(R.string.selectedItem), "Google Vision");
        spinner.setSelection(adapter.getPosition(selectedModel));

        // Ajout d'un listener sur le spinner
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                // On récupère le modèle selectionné dans le spinner
                selectedModel = parent.getSelectedItem().toString();

                // Affichage de la réponse sélectionnée dans un Toast
                Toast.makeText(getApplicationContext(), selectedModel, Toast.LENGTH_SHORT).show();

                // On change le listener du bouton de prédiction en fonction du modèle choisi
                switch (selectedModel){
                    case "Google Vision":
                        predictBtn.setOnClickListener(v -> recognizeTextVision());
                        break;
                    case "ML Kit":
                        predictBtn.setOnClickListener(v -> recognizeTextMlKit());
                        break;
                    case "TFLite (objet)":
                        predictBtn.setOnClickListener(v -> imageClassify());
                        break;
                }

                // On sauvegarde le modèle choisi dans les préférences de l'application
                editor.putString(String.valueOf(R.string.selectedItem), selectedModel);
                editor.apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // On récupère le clipboardManager
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    }

    /**
     * Méthode pour vérifier si une permission est accordée
     * @param permission Permission que l'on souhaite vérifier
     * @return true si la permission est accordée, false sinon
     */
    private boolean checkPermission(String permission){
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Lance l'activité pour choisir une image depuis la galerie du téléphone
     */
    private void chooseImage(){
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        chooseImageActivityLauncher.launch(intent);
    }

    /**
     * Lance l'activité pour prendre une photo avec la camera du téléphone
     */
    private void takePicture(){
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureActivityLauncher.launch(intent);
    }

    /**
     * Copie le texte dans le textView dans le presse-papier du téléphone
     */
    private void copyText(){
        // Récupération du texte dans le textView
        String text = result.getText().toString();

        // Copie du texte dans le presse-papier
        clip = ClipData.newPlainText("Texte copié", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show();
    }

    /**
     * Lance la detection de texte dans l'image avec l'API Vision de Google
     */
    private void recognizeTextVision(){
        if(bitmap != null){
            // Création du recognizer
            TextRecognizer recognizer = new TextRecognizer.Builder(this).build();
            if(recognizer.isOperational()){
                // Conversion de l'image en objet Frame
                Frame frame = new Frame.Builder().setBitmap(bitmap).build();
                // Lancement de la detection
                SparseArray<TextBlock> textBlockSparseArray = recognizer.detect(frame);
                // Traitement des résultats pour l'afficher dans le textView
                StringBuilder stringBuilder = new StringBuilder();
                for(int i = 0; i < textBlockSparseArray.size(); i++){
                    TextBlock textBlock = textBlockSparseArray.valueAt(i);
                    stringBuilder.append(textBlock.getValue());
                    stringBuilder.append("\n");
                }
                // Affichage dans le textView
                result.setText(stringBuilder.toString());
            }else
                Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        }else
            result.setText("Select an image !");
    }

    /**
     * Lance la detection de texte dans l'image avec l'api ML Kit de google
      */
    private void recognizeTextMlKit(){
        if (bitmap != null){
            // Préparation de l'image
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            // Création du recognizer
            com.google.mlkit.vision.text.TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            // On lance la reconnaissance de texte
            recognizer.process(image)
                    // Si la reconnaissance est un succès
                    .addOnSuccessListener(text -> {
                        result.setText(text.getText());
                    })
                    // Si la reconnaissance est un échec
                    .addOnFailureListener(e -> {
                        result.setText("Error");
                    });
        }else {
            result.setText("Select an image !");
            return;
        }
    }

    /**
     * Lance la detection d'objet dans l'image avec le modèle tflite
     */
    private void imageClassify(){
        if(bitmap != null){

            try {
                // On créee un objet Detect
                Detect model = Detect.newInstance(MainActivity.this);

                // On modifie l'image pour qu'elle soit de taille 300x300
                Bitmap img = Bitmap.createScaledBitmap(bitmap, 300, 300, true);
                // On crée un objet TensorImage à partir du bitmap pour la detection
                TensorImage image = TensorImage.fromBitmap(img);

                // On lance la detection d'objet dans l'image
                Detect.Outputs outputs = model.process(image);
                // On récupère le premier résultat de la detection (selui qui a le meilleur score)
                Detect.DetectionResult detectionResult = outputs.getDetectionResultList().get(0);

                // On récupère les informations du résultat
                RectF location = detectionResult.getLocationAsRectF();
                String category = detectionResult.getCategoryAsString();
                float score = detectionResult.getScoreAsFloat();

                // On affiche les résultat dans les textView
                result.setText(category);
                //scoreText.setText(String.format("Score : %s", score));
                //locationText.setText(String.format("Location : (%s,%s,%s,%s)", location.top, location.bottom, location.left, location.right));

                // On ferme le modèle
                model.close();


            } catch (IOException e) {

                // On affiche une alerte si il y a une erreur
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);

                builder.setTitle("Model Error");
                builder.setMessage("The AI model ran with an error.");
                builder.setCancelable(false);
                builder.setPositiveButton("OK", (DialogInterface.OnClickListener) (dialog, which) -> {
                    dialog.cancel();
                });

                AlertDialog dialog = builder.create();
                dialog.show();

            }

        }else {
            result.setText("Select an image !");
        }
    }

    /**
     * Retourne le max d'un tableau de float
     * @param array
     * @return
     */
    int getMax(float[] array){
        int max = 0;
        for (int i = 0; i < array.length; i++){
            if(array[i] > array[max]){
                max = i;
            }
        }
        return max;
    }
}