package lombok.agent.eclipse;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class EclipseParserTransformer {
	private static final String COMPILER_PKG =
		"Lorg/eclipse/jdt/internal/compiler/ast/";
	private static final String TARGET_STATIC_CLASS = "java/lombok/ClassLoaderWorkaround";
	private static final String TARGET_STATIC_METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
	
	private static final Map<String, Class<? extends MethodVisitor>> rewriters;
	
	static {
		Map<String, Class<? extends MethodVisitor>> map = new HashMap<String, Class<? extends MethodVisitor>>();
		map.put(String.format("endParse(I)%sCompilationUnitDeclaration;", COMPILER_PKG), EndParsePatcher.class);
		map.put(String.format("parse(%1$sMethodDeclaration;%1$sCompilationUnitDeclaration;)V", COMPILER_PKG), ParseMethodPatcher.class);
		map.put(String.format("parse(%1$sConstructorDeclaration;%1$sCompilationUnitDeclaration;Z)V", COMPILER_PKG), ParseConstructorPatcher.class);
		map.put(String.format("parse(%1$sInitializer;%1$sTypeDeclaration;%1$sCompilationUnitDeclaration;)V", COMPILER_PKG), ParseInitializerPatcher.class);
		rewriters = Collections.unmodifiableMap(map);
	}
	
	private final byte[] in;
	
	EclipseParserTransformer(byte[] classfileBuffer) {
		in = classfileBuffer;
	}
	
	byte[] transform() {
		ClassReader reader = new ClassReader(in);
		ClassWriter writer = new ClassWriter(reader, 0);
		ClassAdapter adapter = new ParserPatcherAdapter(writer);
		reader.accept(adapter, 0);
		return writer.toByteArray();
	}
	
	public static RuntimeException sneakyThrow(Throwable t) {
		if ( t == null ) throw new NullPointerException("t");
		EclipseParserTransformer.<RuntimeException>sneakyThrow0(t);
		return null;
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void sneakyThrow0(Throwable t) throws T {
		throw (T)t;
	}
	
	private static class ParserPatcherAdapter extends ClassAdapter {
		public ParserPatcherAdapter(ClassVisitor cv) {
			super(cv);
		}
		
		@Override public MethodVisitor visitMethod(int access, String name, String desc,
				String signature, String[] exceptions) {
			MethodVisitor writerVisitor = super.visitMethod(access, name, desc, signature, exceptions);
			Class<? extends MethodVisitor> targetVisitorClass = rewriters.get(name+desc);
			if ( targetVisitorClass == null ) return writerVisitor;
			
			try {
				Constructor<? extends MethodVisitor> c = targetVisitorClass.getDeclaredConstructor(MethodVisitor.class);
				c.setAccessible(true);
				return c.newInstance(writerVisitor);
			} catch ( InvocationTargetException e ) {
				throw sneakyThrow(e.getCause());
			} catch ( Exception e ) {
				//NoSuchMethodException: We know they exist.
				//IllegalAccessException: We called setAccessible.
				//InstantiationException: None of these classes are abstract.
				throw sneakyThrow(e);
			}
		}
	}
	
	private static final int BIT24 = 0x800000;
	
	static class ParseBlockContainerPatcher extends MethodAdapter {
		private final String staticMethodName;
		
		ParseBlockContainerPatcher(MethodVisitor mv, String staticMethodName) {
			super(mv);
			this.staticMethodName = staticMethodName;
		}
		
		@Override public void visitCode() {
			//injects: if ( constructorDeclaration.bits & BIT24 > 0 ) return;
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitFieldInsn(Opcodes.GETFIELD, "org/eclipse/jdt/internal/compiler/ast/ASTNode", "bits", "I");
			mv.visitLdcInsn(Integer.valueOf(BIT24));
			mv.visitInsn(Opcodes.IAND);
			Label l0 = new Label();
			mv.visitJumpInsn(Opcodes.IFLE, l0);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitLabel(l0);
			mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			super.visitCode();
		}
		
		@Override public void visitInsn(int opcode) {
			if ( opcode == Opcodes.RETURN ) {
				//injects: ClassLoaderWorkaround.transformConstructorDeclaration(parser, constructorDeclaration);
				super.visitVarInsn(Opcodes.ALOAD, 0);
				super.visitVarInsn(Opcodes.ALOAD, 1);
				super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_STATIC_CLASS,
						staticMethodName, TARGET_STATIC_METHOD_DESC);
			}
			super.visitInsn(opcode);
		}
	}
	
	static class ParseConstructorPatcher extends ParseBlockContainerPatcher {
		public ParseConstructorPatcher(MethodVisitor mv) {
			super(mv, "transformConstructorDeclaration");
		}
	}
	
	static class ParseMethodPatcher extends ParseBlockContainerPatcher {
		public ParseMethodPatcher(MethodVisitor mv) {
			super(mv, "transformMethodDeclaration");
		}
	}
	
	static class ParseInitializerPatcher extends ParseBlockContainerPatcher {
		public ParseInitializerPatcher(MethodVisitor mv) {
			super(mv, "transformInitializer");
		}
	}
	
	static class EndParsePatcher extends MethodAdapter {
		private static final String TARGET_STATIC_METHOD_NAME = "transformCompilationUnitDeclaration";
		
		EndParsePatcher(MethodVisitor mv) {
			super(mv);
		}
		
		@Override public void visitInsn(int opcode) {
			if ( opcode == Opcodes.ARETURN ) {
				//injects: ClassLoaderWorkaround.transformCUD(parser, compilationUnitDeclaration);
				super.visitInsn(Opcodes.DUP);
				super.visitVarInsn(Opcodes.ALOAD, 0);
				super.visitInsn(Opcodes.SWAP);
				super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_STATIC_CLASS,
						TARGET_STATIC_METHOD_NAME, TARGET_STATIC_METHOD_DESC);
			}
			
			super.visitInsn(opcode);
		}
	}
}