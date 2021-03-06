package com.intive.patronage.toz.pet;

import com.intive.patronage.toz.config.ApiUrl;
import com.intive.patronage.toz.error.exception.NoPermissionException;
import com.intive.patronage.toz.error.model.ErrorResponse;
import com.intive.patronage.toz.error.model.ValidationErrorResponse;
import com.intive.patronage.toz.pet.model.db.Pet;
import com.intive.patronage.toz.pet.model.view.PetRequestBody;
import com.intive.patronage.toz.pet.model.view.PetView;
import com.intive.patronage.toz.status.model.PetStatus;
import com.intive.patronage.toz.storage.StorageProperties;
import com.intive.patronage.toz.storage.StorageService;
import com.intive.patronage.toz.storage.model.db.UploadedFile;
import com.intive.patronage.toz.storage.model.view.UrlView;
import com.intive.patronage.toz.util.ModelMapper;
import com.intive.patronage.toz.util.UserInfoGetter;
import com.intive.patronage.toz.util.validation.ImageValidator;
import io.swagger.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Api(tags = "Pets", description = "Pets management operations (CRUD).")
@RestController
@RequestMapping(value = ApiUrl.PETS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class PetsController {

    private final PetsService petsService;
    private final StorageService storageService;
    private final StorageProperties storageProperties;

    @Autowired
    PetsController(PetsService service, StorageService storageService, StorageProperties storageProperties) {
        this.petsService = service;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
    }

    @ApiOperation(value = "Get all pets", responseContainer = "List", notes =
            "Required roles: SA, TOZ, VOLUNTEER, ANONYMOUS if all fields are present or, " +
                    "Required roles: SA, TOZ if pet records are not complete.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ', 'VOLUNTEER', 'ANONYMOUS')")
    public List<PetView> getAllPets() {
        if (UserInfoGetter.hasCurrentUserAdminRole()) {
            final List<Pet> pets = petsService.findAllPets();
            return convertToView(pets);
        }
        final List<Pet> pets = petsService.findPetsWithFilledFields();
        return convertToView(pets);
    }

    @ApiOperation(value = "Get single pet by id", notes =
            "Required roles: SA, TOZ, VOLUNTEER, ANONYMOUS if all fields are present, or " +
                    "Required roles: SA, TOZ if pet record is not complete.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Pet not found", response = ErrorResponse.class),
    })
    @GetMapping(value = "/{id}")
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ', 'VOLUNTEER', 'ANONYMOUS')")
    public PetView getPetById(@ApiParam(required = true) @PathVariable UUID id) {
        Pet pet = petsService.findById(id);
        if (!UserInfoGetter.hasCurrentUserAdminRole()
                && (pet.getName() == null || pet.getType() == null || pet.getSex() == null)) {
            throw new NoPermissionException(); //TODO: add checking if pet's status is public, when mobiles finish testing
        }
        return convertToView(pet);
    }

    @ApiOperation(value = "Create new pet", response = PetView.class, notes =
            "Required roles: SA, TOZ.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Bad request", response = ValidationErrorResponse.class)
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ')")
    public ResponseEntity<PetView> createPet(@Valid @RequestBody PetRequestBody petView) {
        final Pet createdPet = petsService.createPet(convertToModel(petView));
        final PetView createdPetView = convertToView(createdPet);
        final URI baseLocation = ServletUriComponentsBuilder.fromCurrentRequest()
                .build().toUri();
        final String petLocationString = String.format("/%s/%s", baseLocation, createdPetView.getId());
        final URI location = UriComponentsBuilder.fromUriString(petLocationString).build().toUri();
        return ResponseEntity.created(location)
                .body(createdPetView);
    }

    @ApiOperation(value = "Delete pet", notes =
            "Required roles: SA, TOZ.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Pet not found", response = ErrorResponse.class)
    })
    @DeleteMapping(value = "/{id}")
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ')")
    public ResponseEntity<?> deletePetById(@PathVariable UUID id) {
        petsService.deletePet(id);
        return ResponseEntity.ok().build();
    }

    @ApiOperation(value = "Update pet information", response = PetView.class, notes =
            "Required roles: SA, TOZ.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Pet not found", response = ErrorResponse.class),
            @ApiResponse(code = 400, message = "Bad request", response = ValidationErrorResponse.class)
    })
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ')")
    public PetView updatePet(@PathVariable UUID id, @Valid @RequestBody PetRequestBody petView) {
        final Pet pet = convertToModel(petView);
        final Pet updatedPet = petsService.updatePet(id, pet);
        return convertToView(updatedPet);
    }

    @ApiOperation(value = "Upload main image", notes =
            "Required roles: SA, TOZ.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 422, message = "Invalid image file")
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ')")
    public ResponseEntity<UrlView> uploadFile(@PathVariable UUID id, @RequestParam MultipartFile file) {
        final UploadedFile uploadedFile = prepareAndStoreUploadedFile(file);
        UrlView urlView = createUrlView(uploadedFile);
        petsService.updatePetImageUrl(id, urlView.getUrl());
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .build().toUri();

        return ResponseEntity.created(location)
                .body(urlView);
    }

    @ApiOperation(value = "Upload image to gallery", notes =
            "Required roles: SA, TOZ.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 422, message = "Invalid image file")
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/{id}/gallery", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ')")
    public ResponseEntity<UrlView> uploadGallery(@PathVariable UUID id, @RequestParam MultipartFile file) {
        final UploadedFile uploadedFile = prepareAndStoreUploadedFile(file);
        UrlView urlView = createUrlView(uploadedFile);
        uploadedFile.setFileUrl(urlView.getUrl());
        petsService.addImageToGallery(id, uploadedFile);
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .build().toUri();

        return ResponseEntity.created(location)
                .body(urlView);
    }

    @ApiOperation(value = "Remove image from gallery", notes =
            "Required roles: SA, TOZ.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 404, message = "Pet not found", response = ErrorResponse.class),
    })
    @ResponseStatus(HttpStatus.OK)
    @DeleteMapping(value = "/{petId}/gallery/{imageId}")
    @PreAuthorize("hasAnyAuthority('SA', 'TOZ')")
    public ResponseEntity<?> removeImageFromGallery(@PathVariable UUID petId, @PathVariable UUID imageId) {
        UploadedFile uploadedFile = storageService.get(imageId);
        petsService.removeImageFromGallery(petId, uploadedFile);
        storageService.delete(imageId);
        return ResponseEntity.ok().build();
    }

    private UrlView createUrlView(UploadedFile uploadedFile) {
        UrlView urlView = new UrlView();
        urlView.setUrl(String.format("/%s/%s", this.storageProperties.getStoragePathRoot(),
                uploadedFile.getPath()));
        return urlView;
    }

    private UploadedFile prepareAndStoreUploadedFile(MultipartFile file) {
        ImageValidator.validateImageArgument(file);
        return storageService.store(file);
    }

    private Pet convertToModel(final PetView petView) {
        PetStatus petStatus = null;
        if (petView.getPetStatus() != null) {
            petStatus = new PetStatus();
            petStatus.setId(petView.getPetStatus());
        }
        petView.setPetStatus(null);
        Pet pet = ModelMapper.convertIdentifiableToModel(petView, Pet.class);
        pet.setPetStatus(petStatus);
        return pet;
    }

    private PetView convertToView(final Pet pet) {
        PetView petView;
        if (pet.getPetStatus() != null) {
            UUID statusId = pet.getPetStatus().getId();
            pet.setPetStatus(null);
            petView = ModelMapper.convertIdentifiableToView(pet, PetView.class);
            petView.setPetStatus(statusId);
        } else {
            petView = ModelMapper.convertIdentifiableToView(pet, PetView.class);
        }
        return petView;
    }

    private List<PetView> convertToView(final Collection<Pet> pet) {
        List<PetView> petViews = new ArrayList<>();
        pet.forEach(
                object -> petViews.add(convertToView(object))
        );
        return petViews;
    }
}
