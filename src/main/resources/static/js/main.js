/**
 * Main JavaScript for Heap Dump Analyzer
 */

document.addEventListener('DOMContentLoaded', function() {
    
    // File input change event
    const fileInput = document.getElementById('fileInput');
    const fileNameSpan = document.getElementById('fileName');
    
    if (fileInput && fileNameSpan) {
        fileInput.addEventListener('change', function(e) {
            const file = e.target.files[0];
            if (file) {
                fileNameSpan.textContent = file.name;
            } else {
                fileNameSpan.textContent = 'Choose a file or drag it here';
            }
        });
    }
    
    // Drag and drop functionality
    const uploadBox = document.querySelector('.upload-box');
    
    if (uploadBox) {
        uploadBox.addEventListener('dragover', function(e) {
            e.preventDefault();
            uploadBox.style.backgroundColor = '#EFF6FF';
            uploadBox.style.borderColor = '#2563EB';
        });
        
        uploadBox.addEventListener('dragleave', function(e) {
            e.preventDefault();
            uploadBox.style.backgroundColor = '#F9FAFB';
            uploadBox.style.borderColor = '#3B82F6';
        });
        
        uploadBox.addEventListener('drop', function(e) {
            e.preventDefault();
            uploadBox.style.backgroundColor = '#F9FAFB';
            uploadBox.style.borderColor = '#3B82F6';
            
            const files = e.dataTransfer.files;
            if (files.length > 0) {
                const file = files[0];
                // Check file extension
                const validExtensions = ['.hprof', '.bin', '.dump'];
                const fileName = file.name.toLowerCase();
                const isValid = validExtensions.some(ext => fileName.endsWith(ext));
                
                if (isValid) {
                    fileInput.files = files;
                    fileNameSpan.textContent = file.name;
                } else {
                    alert('Invalid file type. Please upload a .hprof, .bin, or .dump file.');
                }
            }
        });
    }
    
    // Form validation
    const uploadForm = document.getElementById('uploadForm');
    
    if (uploadForm) {
        uploadForm.addEventListener('submit', function(e) {
            const file = fileInput.files[0];
            
            if (!file) {
                e.preventDefault();
                alert('Please select a file to upload.');
                return false;
            }
            
            // Check file size (2GB limit)
            const maxSize = 2 * 1024 * 1024 * 1024; // 2GB in bytes
            if (file.size > maxSize) {
                e.preventDefault();
                alert('File size exceeds 2GB limit. Please upload a smaller file.');
                return false;
            }
            
            // Show loading indicator
            const submitButton = uploadForm.querySelector('button[type="submit"]');
            if (submitButton) {
                submitButton.disabled = true;
                submitButton.innerHTML = '<span>Uploading...</span>';
            }
        });
    }
    
    // Auto-hide alert messages after 5 seconds
    const alerts = document.querySelectorAll('.alert');
    alerts.forEach(function(alert) {
        setTimeout(function() {
            alert.style.transition = 'opacity 0.5s ease';
            alert.style.opacity = '0';
            setTimeout(function() {
                alert.remove();
            }, 500);
        }, 5000);
    });
    
});
