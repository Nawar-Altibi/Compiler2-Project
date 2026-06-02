package compilers.flask.ast.nodes.expressions.access;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class FunctionCallNode extends Expression {

    private Expression function;  // Can be Identifier or AttributeAccess
    private java.util.List<Expression> args;
    private java.util.Map<String, Expression> kwargs;

    public FunctionCallNode(Expression function) {
        this.function = function;
        this.args = new java.util.ArrayList<>();
        this.kwargs = new java.util.HashMap<>();
        function.setParent(this);
    }

    public void addArg(Expression arg) {
        args.add(arg);
        arg.setParent(this);
    }

    public void addKwarg(String name, Expression value) {
        kwargs.put(name, value);
        value.setParent(this);
    }

    public Expression getFunction() {
        return function;
    }

    public java.util.List<Expression> getArgs() {
        return args;
    }

    public java.util.Map<String, Expression> getKwargs() {
        return kwargs;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitFunctionCall(this);
    }

    @Override
    public String getNodeType() {
        return "FunctionCall";
    }
}
