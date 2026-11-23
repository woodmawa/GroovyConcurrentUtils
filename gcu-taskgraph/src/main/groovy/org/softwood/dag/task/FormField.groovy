package org.softwood.dag.task

import groovy.transform.ToString

/**
 * Represents a form field for data collection in a ManualTask.
 */
@ToString(includeNames = true, excludes = ['constraints'])
class FormField {
    
    /** Field name/identifier */
    String name
    
    /** Field type */
    FieldType type = FieldType.TEXT
    
    /** Human-readable label */
    String label
    
    /** Help text/description */
    String helpText
    
    /** Whether this field is required */
    boolean required = false
    
    /** Default value */
    Object defaultValue
    
    /** Additional constraints (min, max, pattern, options, etc.) */
    Map<String, Object> constraints = [:]
    
    /**
     * Field types supported by ManualTask forms
     */
    enum FieldType {
        TEXT,           // Single-line text input
        TEXTAREA,       // Multi-line text input
        NUMBER,         // Numeric input
        BOOLEAN,        // Checkbox/toggle
        DATE,           // Date picker
        DATETIME,       // Date and time picker
        SELECT,         // Dropdown selection
        MULTISELECT,    // Multiple selection
        EMAIL,          // Email address input
        URL,            // URL input
        FILE            // File upload
    }
    
    /**
     * Validate a value against this field's constraints
     */
    boolean validate(Object value) {
        // Required check
        if (required && (value == null || value.toString().trim().isEmpty())) {
            return false
        }
        
        // If not required and empty, it's valid
        if (value == null) {
            return true
        }
        
        // Type-specific validation
        switch (type) {
            case FieldType.NUMBER:
                return validateNumber(value)
            case FieldType.EMAIL:
                return validateEmail(value)
            case FieldType.URL:
                return validateUrl(value)
            case FieldType.SELECT:
            case FieldType.MULTISELECT:
                return validateSelection(value)
            default:
                return true  // Other types don't have specific validation
        }
    }
    
    private boolean validateNumber(Object value) {
        try {
            def num = value as BigDecimal
            
            if (constraints.min != null && num < (constraints.min as BigDecimal)) {
                return false
            }
            
            if (constraints.max != null && num > (constraints.max as BigDecimal)) {
                return false
            }
            
            return true
        } catch (Exception e) {
            return false
        }
    }
    
    private boolean validateEmail(Object value) {
        def emailPattern = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}$/
        return value.toString() ==~ emailPattern
    }
    
    private boolean validateUrl(Object value) {
        try {
            new URL(value.toString())
            return true
        } catch (Exception e) {
            return false
        }
    }
    
    private boolean validateSelection(Object value) {
        if (constraints.options == null) {
            return true
        }
        
        def options = constraints.options as List
        
        if (type == FieldType.MULTISELECT && value instanceof Collection) {
            return value.every { it in options }
        } else {
            return value in options
        }
    }
}
