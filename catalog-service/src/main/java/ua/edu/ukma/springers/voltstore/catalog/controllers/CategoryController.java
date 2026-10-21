package ua.edu.ukma.springers.voltstore.catalog.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategorySearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.openapi.Problem;
import ua.edu.ukma.springers.voltstore.catalog.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.catalog.services.CategoryService;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.catalog.openapi.OpenApiConfig.BEARER_AUTH;

@Tag(name = "Categories")
@RestController
@RequestMapping("/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;

    // EP-CAT-10: public, query parameters bound from ?search=&page=&size=
    @Operation(summary = "List or search categories (EP-CAT-10)", description = "Public. Ordered by name.")
    @ApiResponse(responseCode = "200", description = "One page of categories")
    @ApiResponse(responseCode = "400", description = "Invalid paging parameters",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @GetMapping
    public PageResponse<CategoryResponse> listCategories(@Valid @ModelAttribute @ParameterObject CategorySearchQuery query) {
        return categoryService.listCategories(query);
    }

    @Operation(summary = "Create a category (EP-CAT-06)", description = "Role: CATALOG_MANAGER.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Invalid name",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CATALOG_MANAGER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The name is already in use (case-insensitive)",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@RequestBody @Valid CategoryRequest request) {
        return categoryService.createCategory(request);
    }

    // No @Valid: the spec requires the 404 check before body validation, so the service validates
    @Operation(summary = "Rename a category (EP-CAT-07)", description = "Role: CATALOG_MANAGER.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The renamed category")
    @ApiResponse(responseCode = "400", description = "Invalid name",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CATALOG_MANAGER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No category with this ID",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The name is used by another category",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PutMapping("/{id}")
    public CategoryResponse renameCategory(@PathVariable UUID id, @RequestBody CategoryRequest request) {
        return categoryService.renameCategory(id, request);
    }

    // The spec returns 200 OK with no body (not 204)
    @Operation(summary = "Delete a category (EP-CAT-08)", description = "Role: CATALOG_MANAGER.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "Deleted")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CATALOG_MANAGER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No category with this ID",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "Products still belong to the category",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public void deleteCategory(@PathVariable UUID id) {
        categoryService.deleteCategory(id);
    }
}
