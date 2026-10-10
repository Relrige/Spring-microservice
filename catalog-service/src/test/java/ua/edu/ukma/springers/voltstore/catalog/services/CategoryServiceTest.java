package ua.edu.ukma.springers.voltstore.catalog.services;

import jakarta.validation.Validation;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.CategoryResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryHasProductsException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNameAlreadyInUseException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {
    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductRepository productRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        RequestValidator requestValidator = new RequestValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        categoryService = new CategoryService(categoryRepository, productRepository, requestValidator);
    }

    // --- create ---

    @Test
    void createCategory_savesTrimmedNameAndReturnsIt() {
        CategoryResponse response = categoryService.createCategory(request("  Laptops  "));

        verify(categoryRepository).existsByNameIgnoreCase("Laptops");
        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Laptops");
        assertThat(response.getName()).isEqualTo("Laptops");
    }

    @Test
    void createCategory_duplicateName_throwsAndDoesNotSave() {
        when(categoryRepository.existsByNameIgnoreCase("laptops")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(request(" laptops ")))
                .isInstanceOf(CategoryNameAlreadyInUseException.class)
                .hasMessage("Category name already in use");

        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCategory_uniqueIndexViolation_mapsToNameAlreadyInUse() {
        when(categoryRepository.saveAndFlush(any())).thenThrow(constraintViolation("categories_name_lower_key"));

        assertThatThrownBy(() -> categoryService.createCategory(request("Laptops")))
                .isInstanceOf(CategoryNameAlreadyInUseException.class);
    }

    @Test
    void createCategory_otherIntegrityViolation_isRethrown() {
        DataIntegrityViolationException other = constraintViolation("some_other_constraint");
        when(categoryRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> categoryService.createCategory(request("Laptops"))).isSameAs(other);
    }

    // --- rename ---

    @Test
    void renameCategory_updatesNameExcludingItselfFromDuplicateCheck() {
        UUID id = UUID.randomUUID();
        Category category = new Category("laptops");
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));

        CategoryResponse response = categoryService.renameCategory(id, request(" Laptops "));

        verify(categoryRepository).existsByNameIgnoreCaseAndIdNot("Laptops", id);
        assertThat(category.getName()).isEqualTo("Laptops");
        assertThat(response.getName()).isEqualTo("Laptops");
    }

    @Test
    void renameCategory_notFound_throwsBeforeValidatingBody() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        // Blank name would be a 400, but the spec checks existence first
        assertThatThrownBy(() -> categoryService.renameCategory(id, request("  ")))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void renameCategory_blankName_throwsValidationError() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(new Category("Laptops")));

        assertThatThrownBy(() -> categoryService.renameCategory(id, request("  ")))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        e -> assertThat(e.getErrors()).containsKey("name"));
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void renameCategory_nameTakenByAnotherCategory_throwsConflict() {
        UUID id = UUID.randomUUID();
        Category category = new Category("Phones");
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("laptops", id)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.renameCategory(id, request("laptops")))
                .isInstanceOf(CategoryNameAlreadyInUseException.class);
        assertThat(category.getName()).isEqualTo("Phones");
    }

    @Test
    void renameCategory_uniqueIndexViolation_mapsToNameAlreadyInUse() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(new Category("Phones")));
        when(categoryRepository.saveAndFlush(any())).thenThrow(constraintViolation("categories_name_lower_key"));

        assertThatThrownBy(() -> categoryService.renameCategory(id, request("Laptops")))
                .isInstanceOf(CategoryNameAlreadyInUseException.class);
    }

    // --- delete ---

    @Test
    void deleteCategory_withoutProducts_deletes() {
        UUID id = UUID.randomUUID();
        Category category = new Category("Laptops");
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));
        when(productRepository.existsByCategoryId(id)).thenReturn(false);

        categoryService.deleteCategory(id);

        verify(categoryRepository).delete(category);
        verify(categoryRepository).flush();
    }

    @Test
    void deleteCategory_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(id))
                .isInstanceOf(CategoryNotFoundException.class);
        verifyNoInteractions(productRepository);
    }

    @Test
    void deleteCategory_withProducts_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(new Category("Laptops")));
        when(productRepository.existsByCategoryId(id)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory(id))
                .isInstanceOf(CategoryHasProductsException.class);
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void deleteCategory_foreignKeyViolation_mapsToHasProducts() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(new Category("Laptops")));
        doThrow(constraintViolation("fk_products_category")).when(categoryRepository).flush();

        assertThatThrownBy(() -> categoryService.deleteCategory(id))
                .isInstanceOf(CategoryHasProductsException.class);
    }

    private static CategoryRequest request(String name) {
        CategoryRequest request = new CategoryRequest();
        request.setName(name);
        return request;
    }

    static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException("violation",
                new ConstraintViolationException("violation", new SQLException("violation"), constraintName));
    }
}
