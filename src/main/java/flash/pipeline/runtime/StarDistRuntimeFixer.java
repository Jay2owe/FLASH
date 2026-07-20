package flash.pipeline.runtime;

import flash.pipeline.stardist.RuntimeChecker;

import java.io.File;
import java.util.List;

final class StarDistRuntimeFixer extends AbstractJarDependencyFixer {

    StarDistRuntimeFixer() {
        super(10, "StarDist runtime verified.", "StarDist repair completed.");
    }

    @Override
    protected File getFijiDir() {
        return RuntimeChecker.getFijiDir();
    }

    @Override
    protected List<String> check(File fijiDir, DependencySpec spec) {
        return RuntimeChecker.check(fijiDir);
    }

    @Override
    protected List<String> repair(File fijiDir, DependencySpec spec) {
        return RuntimeChecker.repair(fijiDir);
    }
}
