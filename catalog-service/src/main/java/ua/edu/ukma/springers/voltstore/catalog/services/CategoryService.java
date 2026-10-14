package ua.edu.ukma.springers.voltstore.catalog.services;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategorySearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryHasProductsException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNameAlreadyInUseException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.utils.CategoryNameNormalizer;
import ua.edu.ukma.springers.voltstore.catalog.utils.ConstraintViolations;
import ua.edu.ukma.springers.voltstore.catalog.utils.Paging;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final RequestValidator requestValidator;

    // EP-CAT-06. The request is already validated by @Valid in the controller.
    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        String name = CategoryNameNormalizer.normalize(request.getName());
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new CategoryNameAlreadyInUseException();
        }
        Category category = new Category(name);
        saveAndFlush(category);
        return CategoryResponse.from(category);
    }

    // EP-CAT-07. Order of checks follows the spec: 404, then 400, then 409.
    @Transactional
    public CategoryResponse renameCategory(UUID id, CategoryRequest request) {
        Category category = findCategory(id);
        requestValidator.validate(request);

        String name = CategoryNameNormalizer.normalize(request.getName());
        // Excluding the category itself makes a no-op rename or a casing-only change succeed
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new CategoryNameAlreadyInUseException();
        }
        category.rename(name);
        saveAndFlush(category);
        return CategoryResponse.from(category);
    }

    // EP-CAT-08. Categories are hard-deleted, but only when no product (of any status) references them.
    @Transactional
    public void deleteCategory(UUID id) {
        Category category = findCategory(id);
        if (productRepository.existsByCategoryId(id)) {
            throw new CategoryHasProductsException();
        }
        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // Race condition: a product was assigned to this category after the check above;
            // the ON DELETE RESTRICT foreign key blocked the delete
            if (ConstraintViolations.isViolationOf(e, ConstraintViolations.PRODUCT_CATEGORY_FK)) {
                throw new CategoryHasProductsException();
            }
            throw e;
        }
    }

    // EP-CAT-10 (an addition to the spec). Even a short list goes through paging, so behaviour is uniform.
    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> listCategories(CategorySearchQuery query) {
        // Stable order by name; id breaks ties so pages never overlap
        Pageable pageable = Paging.pageRequest(query.getPage(), query.getSize(), Sort.by("name", "id"));
        String search = query.getSearch() != null ? query.getSearch().trim() : "";
        Page<Category> page = search.isEmpty()
                ? categoryRepository.findAll(pageable)
                : categoryRepository.findByNameContainingIgnoreCase(search, pageable);
        return PageResponse.from(page, CategoryResponse::from);
    }

    private Category findCategory(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }

    private void saveAndFlush(Category category) {
        try {
            // Flush inside the try block so a unique index violation surfaces here, not at commit time
            categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            // Race condition: another request took the same name after our pre-check
            if (ConstraintViolations.isViolationOf(e, ConstraintViolations.CATEGORY_NAME_UNIQUE)) {
                throw new CategoryNameAlreadyInUseException();
            }
            throw e;
        }
    }
}
