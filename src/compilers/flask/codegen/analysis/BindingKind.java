package compilers.flask.codegen.analysis;

/**
 * Runtime namespace used by a resolved Python name operation.
 */
public enum BindingKind {
    /** Module namespace or the dynamic namespace of a class body. */
    NAME,

    /** A non-captured local slot in a function frame. */
    FAST,

    /** Module global namespace; loads fall back to builtins at runtime. */
    GLOBAL,

    /** A cell variable owned here or a free cell supplied by an enclosing block. */
    DEREF
}


/*
رجية
الخلاصة في جدول واحد
النوع	أين يظهر غالبًا؟	مثال
NAME	ملف Python أو جسم class	age = 20
FAST	متغير أو parameter محلي عادي داخل دالة	total = number + 1
GLOBAL	اسم خارجي تستخدمه دالة	return price + tax
DEREF	متغير closure بين دالتين متداخلتين	inner() تقرأ message من outer()
*/
