(function () {
    "use strict";

    var productsApi = "/api/products";

    function responseError(response) {
        return response.text().then(function (message) {
            throw new Error(message.trim() || "Request failed with status " + response.status);
        });
    }

    function bindDeleteButtons() {
        document.querySelectorAll("[data-delete-product]").forEach(function (button) {
            button.addEventListener("click", function () {
                var id = button.getAttribute("data-delete-product");
                var name = button.getAttribute("data-product-name") || "this product";
                if (!window.confirm("Delete " + name + "?")) {
                    return;
                }

                button.disabled = true;
                fetch(productsApi + "/" + encodeURIComponent(id), {
                    method: "DELETE"
                }).then(function (response) {
                    if (!response.ok) {
                        return responseError(response);
                    }
                    window.location.href = "/index.html";
                }).catch(function (error) {
                    button.disabled = false;
                    window.alert(error.message);
                });
            });
        });
    }

    function bindEditLinks() {
        document.querySelectorAll("[data-edit-product]").forEach(function (link) {
            link.addEventListener("click", function (event) {
                event.preventDefault();
                var id = link.getAttribute("data-edit-product");
                window.location.href = link.getAttribute("href")
                        + "?id=" + encodeURIComponent(id);
            });
        });
    }

    function initEditForm() {
        var form = document.querySelector("[data-edit-product-form]");
        if (!form) {
            return;
        }

        var status = form.querySelector("[data-form-status]");
        var heading = document.querySelector("[data-edit-heading]");
        var id = new URLSearchParams(window.location.search).get("id");
        if (!id) {
            status.textContent = "No product id was provided.";
            form.querySelector("button[type='submit']").disabled = true;
            return;
        }

        fetch(productsApi + "/" + encodeURIComponent(id))
            .then(function (response) {
                return response.ok ? response.json() : responseError(response);
            })
            .then(function (product) {
                form.elements.name.value = product.name;
                form.elements.description.value = product.description;
                form.elements.price.value = product.price;
                heading.textContent = "Edit Product: " + product.name;
            })
            .catch(function (error) {
                status.textContent = error.message;
                form.querySelector("button[type='submit']").disabled = true;
            });

        form.addEventListener("submit", function (event) {
            event.preventDefault();
            var submit = form.querySelector("button[type='submit']");
            submit.disabled = true;
            status.textContent = "Saving...";

            fetch(productsApi + "/" + encodeURIComponent(id), {
                method: "PUT",
                headers: {"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
                body: new URLSearchParams(new FormData(form)).toString()
            }).then(function (response) {
                if (!response.ok) {
                    return responseError(response);
                }
                window.location.href = "/index.html";
            }).catch(function (error) {
                submit.disabled = false;
                status.textContent = error.message;
            });
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        bindEditLinks();
        bindDeleteButtons();
        initEditForm();
    });
}());
