package flash.pipeline.runtime;

import java.io.File;
import java.util.List;

final class ExcelRuntimeFixer extends AbstractJarDependencyFixer {

    ExcelRuntimeFixer() {
        super(12, "Excel runtime verified.", "Excel runtime repair completed.");
    }

    @Override
    protected File getFijiDir() {
        return ExcelRuntimeChecker.getFijiDir();
    }

    @Override
    protected List<String> check(File fijiDir, DependencySpec spec) {
        return ExcelRuntimeChecker.check(fijiDir);
    }

    @Override
    protected List<String> repair(File fijiDir, DependencySpec spec) {
        return ExcelRuntimeChecker.repair(fijiDir);
    }
}
