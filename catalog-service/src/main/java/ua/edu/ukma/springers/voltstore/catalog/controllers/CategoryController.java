package ua.edu.ukma.springers.voltstore.catalog.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategorySearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.services.CategoryService;

import java.util.UUID;

@RestController
@RequestMapping("/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;

    // EP-CAT-10: public, query parameters bound from ?search=&page=&size=
    @GetMapping
    public PageResponse<CategoryResponse> listCategories(@Valid @ModelAttribute CategorySearchQuery query) {
        return categoryService.listCategories(query);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@RequestBody @Valid CategoryRequest request) {
        return categoryService.createCategory(request);
    }

    // No @Valid: the spec requires the 404 check before body validation, so the service validates
    @PutMapping("/{id}")
    public CategoryResponse renameCategory(@PathVariable UUID id, @RequestBody CategoryRequest request) {
        return categoryService.renameCategory(id, request);
    }

    // The spec returns 200 OK with no body (not 204)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public void deleteCategory(@PathVariable UUID id) {
        categoryService.deleteCategory(id);
    }
}
